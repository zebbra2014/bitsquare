/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade;

import io.bitsquare.btc.BlockChainService;
import io.bitsquare.btc.WalletService;
import io.bitsquare.common.handlers.ErrorMessageHandler;
import io.bitsquare.common.handlers.ResultHandler;
import io.bitsquare.crypto.EncryptionService;
import io.bitsquare.crypto.SignatureService;
import io.bitsquare.fiat.FiatAccount;
import io.bitsquare.offer.Direction;
import io.bitsquare.offer.Offer;
import io.bitsquare.offer.OfferBookService;
import io.bitsquare.p2p.AddressService;
import io.bitsquare.p2p.EncryptedMailboxMessage;
import io.bitsquare.p2p.MailboxMessage;
import io.bitsquare.p2p.MailboxService;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.MessageService;
import io.bitsquare.p2p.Peer;
import io.bitsquare.p2p.listener.SendMessageListener;
import io.bitsquare.persistence.Persistence;
import io.bitsquare.trade.handlers.TradeResultHandler;
import io.bitsquare.trade.handlers.TransactionResultHandler;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityModel;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityProtocol;
import io.bitsquare.trade.protocol.availability.messages.ReportOfferAvailabilityMessage;
import io.bitsquare.trade.protocol.availability.messages.RequestIsOfferAvailableMessage;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferModel;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferProtocol;
import io.bitsquare.trade.protocol.trade.messages.TradeMessage;
import io.bitsquare.trade.protocol.trade.offerer.OffererAsBuyerProtocol;
import io.bitsquare.trade.protocol.trade.offerer.models.OffererAsBuyerModel;
import io.bitsquare.trade.protocol.trade.taker.TakerAsSellerProtocol;
import io.bitsquare.trade.protocol.trade.taker.models.TakerAsSellerModel;
import io.bitsquare.user.AccountSettings;
import io.bitsquare.user.User;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

import java.io.Serializable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public class TradeManager {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    private final AccountSettings accountSettings;
    private final Persistence persistence;
    private final MessageService messageService;
    private MailboxService mailboxService;
    private final AddressService addressService;
    private final BlockChainService blockChainService;
    private final WalletService walletService;
    private final SignatureService signatureService;
    private EncryptionService<MailboxMessage> encryptionService;
    private final OfferBookService offerBookService;

    private final Map<String, TakerAsSellerProtocol> takerAsSellerProtocolMap = new HashMap<>();
    private final Map<String, OffererAsBuyerProtocol> offererAsBuyerProtocolMap = new HashMap<>();
    private final Map<String, CheckOfferAvailabilityProtocol> checkOfferAvailabilityProtocolMap = new HashMap<>();

    private final ObservableMap<String, Trade> openOfferTrades = FXCollections.observableHashMap();
    private final ObservableMap<String, Trade> pendingTrades = FXCollections.observableHashMap();
    private final ObservableMap<String, Trade> closedTrades = FXCollections.observableHashMap();
    private final Map<String, MailboxMessage> mailboxMessages = new HashMap<>();

    private Trade currentPendingTrade;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user, AccountSettings accountSettings, Persistence persistence,
                        MessageService messageService, MailboxService mailboxService, AddressService addressService, BlockChainService blockChainService,
                        WalletService walletService, SignatureService signatureService, EncryptionService<MailboxMessage> encryptionService,
                        OfferBookService offerBookService) {
        this.user = user;
        this.accountSettings = accountSettings;
        this.persistence = persistence;
        this.messageService = messageService;
        this.mailboxService = mailboxService;
        this.addressService = addressService;
        this.blockChainService = blockChainService;
        this.walletService = walletService;
        this.signatureService = signatureService;
        this.encryptionService = encryptionService;
        this.offerBookService = offerBookService;

        Serializable openOfferTradesObject = persistence.read(this, "openOfferTrades");
        if (openOfferTradesObject instanceof Map<?, ?>) {
            openOfferTrades.putAll((Map<String, Trade>) openOfferTradesObject);
        }

        Serializable pendingTradesObject = persistence.read(this, "pendingTrades");
        if (pendingTradesObject instanceof Map<?, ?>) {
            pendingTrades.putAll((Map<String, Trade>) pendingTradesObject);
        }

        Serializable closedTradesObject = persistence.read(this, "closedTrades");
        if (closedTradesObject instanceof Map<?, ?>) {
            closedTrades.putAll((Map<String, Trade>) closedTradesObject);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // When all services are initialized we create the protocols for our open offers and persisted not completed pendingTrades
    // BuyerAcceptsOfferProtocol listens for take offer requests, so we need to instantiate it early.
    public void onAllServicesInitialized() {
        for (Map.Entry<String, Trade> entry : openOfferTrades.entrySet()) {
            createOffererAsBuyerProtocol(entry.getValue());
        }
        for (Map.Entry<String, Trade> entry : pendingTrades.entrySet()) {
            // We continue an interrupted trade.
            // TODO if the peer has changed its IP address, we need to make another findPeer request. At the moment we use the peer stored in trade to
            // continue the trade, but that might fail.
            Trade trade = entry.getValue();
            if (isMyOffer(trade.getOffer())) {
                createOffererAsBuyerProtocol(trade);
            }
            else {
                createTakerAsSellerProtocol(trade);
            }
        }

        mailboxService.getAllMessages(user.getP2PSigPubKey(),
                (encryptedMailboxMessages) -> {
                    decryptMailboxMessages(encryptedMailboxMessages);
                    emptyMailbox();
                });

        messageService.addMessageHandler(this::handleMessage);
    }

    public boolean isMyOffer(Offer offer) {
        return offer.getP2PSigPubKey().equals(user.getP2PSigPubKey());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void placeOffer(String id,
                           Direction direction,
                           Fiat price,
                           Coin amount,
                           Coin minAmount,
                           TransactionResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {

        FiatAccount currentFiatAccount = user.getCurrentBankAccount().get();
        Offer offer = new Offer(id,
                user.getP2PSigPubKey(),
                direction,
                price.getValue(),
                amount,
                minAmount,
                currentFiatAccount.getFiatAccountType(),
                currentFiatAccount.getCurrency(),
                currentFiatAccount.getCountry(),
                currentFiatAccount.getUid(),
                accountSettings.getAcceptedArbitrators(),
                accountSettings.getSecurityDeposit(),
                accountSettings.getAcceptedCountries(),
                accountSettings.getAcceptedLanguageLocales());

        PlaceOfferModel model = new PlaceOfferModel(offer, walletService, offerBookService);

        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(
                model,
                (transaction) -> {
                    Trade trade = new Trade(offer);
                    openOfferTrades.put(trade.getId(), trade);
                    persistOpenOffers();
                    createOffererAsBuyerProtocol(trade);
                    resultHandler.handleResult(transaction);
                },
                (message) -> errorMessageHandler.handleErrorMessage(message)
        );

        placeOfferProtocol.placeOffer();
    }

    public void removeOpenOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        removeOpenOffer(offer, resultHandler, errorMessageHandler, true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer) {
        if (!checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {
            CheckOfferAvailabilityModel model = new CheckOfferAvailabilityModel(
                    offer,
                    messageService,
                    addressService);

            CheckOfferAvailabilityProtocol protocol = new CheckOfferAvailabilityProtocol(model,
                    () -> disposeCheckOfferAvailabilityRequest(offer),
                    (errorMessage) -> disposeCheckOfferAvailabilityRequest(offer));
            checkOfferAvailabilityProtocolMap.put(offer.getId(), protocol);
            protocol.checkOfferAvailability();
        }
        else {
            log.error("That should never happen: onCheckOfferAvailability already called for offer with ID:" + offer.getId());
        }
    }

    // When closing take offer view, we are not interested in the onCheckOfferAvailability result anymore, so remove from the map
    public void cancelCheckOfferAvailabilityRequest(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }

    public void requestTakeOffer(Coin amount, Offer offer, TradeResultHandler tradeResultHandler) {
        CheckOfferAvailabilityModel model = new CheckOfferAvailabilityModel(offer, messageService, addressService);
        CheckOfferAvailabilityProtocol protocol = new CheckOfferAvailabilityProtocol(model,
                () -> {
                    disposeCheckOfferAvailabilityRequest(offer);
                    if (offer.getState() == Offer.State.AVAILABLE) {
                        Trade trade = takeAvailableOffer(amount, offer, model.getPeer());
                        tradeResultHandler.handleTradeResult(trade);
                    }
                },
                (errorMessage) -> disposeCheckOfferAvailabilityRequest(offer));
        checkOfferAvailabilityProtocolMap.put(offer.getId(), protocol);
        protocol.checkOfferAvailability();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onFiatPaymentStarted(String tradeId) {
        // TODO remove if check when persistence is impl.
        if (offererAsBuyerProtocolMap.containsKey(tradeId)) {
            offererAsBuyerProtocolMap.get(tradeId).onFiatPaymentStarted();
            persistPendingTrades();
        }
    }

    public void onFiatPaymentReceived(String tradeId) {
        takerAsSellerProtocolMap.get(tradeId).onFiatPaymentReceived();
    }

    public void onWithdrawAtTradeCompleted(Trade trade) {
        closeTrade(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from Offerbook when offer gets removed from DHT
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onOfferRemovedFromRemoteOfferBook(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Offerer handles those requests
    private void handleMessage(Message message, Peer sender) {
        if (message instanceof RequestIsOfferAvailableMessage) {
            String offerId = ((RequestIsOfferAvailableMessage) message).offerId;
            checkNotNull(offerId);

            ReportOfferAvailabilityMessage reportOfferAvailabilityMessage = new ReportOfferAvailabilityMessage(offerId, isOfferOpen(offerId));
            messageService.sendMessage(sender, reportOfferAvailabilityMessage, new SendMessageListener() {
                @Override
                public void handleResult() {
                    // Offerer does not do anything at that moment. Peer might only watch the offer and does nto start a trade.
                    log.trace("ReportOfferAvailabilityMessage successfully arrived at peer");
                }

                @Override
                public void handleFault() {
                    log.warn("Sending ReportOfferAvailabilityMessage failed.");
                }
            });
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableMap<String, Trade> getOpenOfferTrades() {
        return openOfferTrades;
    }

    public ObservableMap<String, Trade> getPendingTrades() {
        return pendingTrades;
    }

    public ObservableMap<String, Trade> getClosedTrades() {
        return closedTrades;
    }

    public Trade getCurrentPendingTrade() {
        return currentPendingTrade;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void removeOpenOffer(Offer offer,
                                 ResultHandler resultHandler,
                                 ErrorMessageHandler errorMessageHandler,
                                 boolean removeFromBuyerAcceptsOfferProtocolMap) {
        String offerId = offer.getId();
        offerBookService.removeOffer(offer,
                () -> {
                    if (openOfferTrades.containsKey(offerId)) {
                        openOfferTrades.remove(offerId);
                        disposeCheckOfferAvailabilityRequest(offer);
                        persistOpenOffers();
                        if (removeFromBuyerAcceptsOfferProtocolMap && offererAsBuyerProtocolMap.containsKey(offerId)) {
                            offererAsBuyerProtocolMap.get(offerId).cleanup();
                            offererAsBuyerProtocolMap.remove(offerId);
                        }

                        resultHandler.handleResult();
                    }
                    else {
                        log.error("Locally stored offers does not contain the offer with the ID " + offerId);
                        errorMessageHandler.handleErrorMessage("Locally stored offers does not contain the offer with the ID " + offerId);
                    }
                },
                (message, throwable) -> errorMessageHandler.handleErrorMessage(message));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void disposeCheckOfferAvailabilityRequest(Offer offer) {
        if (checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {
            CheckOfferAvailabilityProtocol protocol = checkOfferAvailabilityProtocolMap.get(offer.getId());
            protocol.cancel();
            protocol.cleanup();
            checkOfferAvailabilityProtocolMap.remove(offer.getId());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Trade takeAvailableOffer(Coin amount, Offer offer, Peer peer) {
        Trade trade = createTrade(offer);
        trade.setTradeAmount(amount);
        trade.setTradingPeer(peer);

        TakerAsSellerProtocol sellerTakesOfferProtocol = createTakerAsSellerProtocol(trade);
        sellerTakesOfferProtocol.takeAvailableOffer();
        return trade;
    }

    private Trade createTrade(Offer offer) {
        if (pendingTrades.containsKey(offer.getId()))
            log.error("That must never happen: Trades contains already an trade with the ID " + offer.getId());

        Trade trade = new Trade(offer);
        pendingTrades.put(offer.getId(), trade);
        persistPendingTrades();

        currentPendingTrade = trade;

        return trade;
    }

    private TakerAsSellerProtocol createTakerAsSellerProtocol(Trade trade) {
        trade.processStateProperty().addListener((ov, oldValue, newValue) -> {
            log.debug("trade state = " + newValue);
            switch (newValue) {
                case INIT:
                    break;
                case TAKE_OFFER_FEE_TX_CREATED:
                case DEPOSIT_PUBLISHED:
                case DEPOSIT_CONFIRMED:
                case FIAT_PAYMENT_STARTED:
                case FIAT_PAYMENT_RECEIVED:
                case PAYOUT_PUBLISHED:
                    persistPendingTrades();
                    break;
                case MESSAGE_SENDING_FAILED:
                case FAULT:
                    closeTrade(trade);
                    break;
                default:
                    log.warn("Unhandled trade state: " + newValue);
                    break;
            }
        });

        TakerAsSellerModel model = new TakerAsSellerModel(
                trade,
                messageService,
                mailboxService,
                walletService,
                blockChainService,
                signatureService,
                user,
                persistence);

        TakerAsSellerProtocol protocol = new TakerAsSellerProtocol(model);
        takerAsSellerProtocolMap.put(trade.getId(), protocol);

        if (mailboxMessages.containsKey(trade.getId())) {
            log.debug("TakerAsSellerProtocol setMailboxMessage " + trade.getId());
            protocol.setMailboxMessage(mailboxMessages.get(trade.getId()));
        }
        return protocol;
    }


    private void createOffererAsBuyerProtocol(Trade trade) {
        OffererAsBuyerModel model = new OffererAsBuyerModel(
                trade,
                messageService,
                mailboxService,
                walletService,
                blockChainService,
                signatureService,
                user,
                persistence);


        // TODO check, remove listener
        trade.processStateProperty().addListener((ov, oldValue, newValue) -> {
            log.debug("trade state = " + newValue);
            switch (newValue) {
                case INIT:
                    break;
                case TAKE_OFFER_FEE_TX_CREATED:
                    persistPendingTrades();
                    break;
                case DEPOSIT_PUBLISHED:
                    removeOpenOffer(trade.getOffer(),
                            () -> log.debug("remove offer was successful"),
                            (message) -> log.error(message),
                            false);

                    // after we have published the deposit tx we add that trade to the pendingTrades
                    if (pendingTrades.containsKey(trade.getId()))
                        log.error("That must never happen: Trades contains already an trade with the ID " + trade.getId());
                    pendingTrades.put(trade.getId(), trade);
                    persistPendingTrades();
                    break;
                case DEPOSIT_CONFIRMED:
                case FIAT_PAYMENT_STARTED:
                case FIAT_PAYMENT_RECEIVED:
                case PAYOUT_PUBLISHED:
                    persistPendingTrades();
                    break;
                case TAKE_OFFER_FEE_PUBLISH_FAILED:
                case MESSAGE_SENDING_FAILED:
                case FAULT:
                    closeTrade(trade);
                    break;
                default:
                    log.warn("Unhandled trade state: " + newValue);
                    break;
            }
        });

        OffererAsBuyerProtocol protocol = new OffererAsBuyerProtocol(model);
        offererAsBuyerProtocolMap.put(trade.getId(), protocol);
        if (mailboxMessages.containsKey(trade.getId())) {
            log.debug("OffererAsBuyerProtocol setMailboxMessage " + trade.getId());
            protocol.setMailboxMessage(mailboxMessages.get(trade.getId()));
        }
    }

    private void closeTrade(Trade trade) {
        if (pendingTrades.containsKey(trade.getId())) {
            pendingTrades.remove(trade.getId());
            persistPendingTrades();
        }

        if (takerAsSellerProtocolMap.containsKey(trade.getId())) {
            takerAsSellerProtocolMap.get(trade.getId()).cleanup();
            takerAsSellerProtocolMap.remove(trade.getId());
        }
        else if (offererAsBuyerProtocolMap.containsKey(trade.getId())) {
            offererAsBuyerProtocolMap.get(trade.getId()).cleanup();
            offererAsBuyerProtocolMap.remove(trade.getId());
        }

        if (!closedTrades.containsKey(trade.getId())) {
            closedTrades.put(trade.getId(), trade);
            persistClosedTrades();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mailbox
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void decryptMailboxMessages(List<EncryptedMailboxMessage> encryptedMailboxMessages) {
        log.trace("applyMailboxMessage encryptedMailboxMessage.size=" + encryptedMailboxMessages.size());
        for (EncryptedMailboxMessage encrypted : encryptedMailboxMessages) {
            try {
                MailboxMessage mailboxMessage = encryptionService.decryptToObject(user.getP2pEncryptPrivateKey(), encrypted.getEncryptionPackage());

                if (mailboxMessage instanceof TradeMessage) {
                    String tradeId = ((TradeMessage) mailboxMessage).tradeId;
                    mailboxMessages.put(tradeId, mailboxMessage);
                    log.trace("added mailboxMessage with tradeID " + tradeId);
                    if (takerAsSellerProtocolMap.containsKey(tradeId)) {
                        takerAsSellerProtocolMap.get(tradeId).setMailboxMessage(mailboxMessage);
                        log.trace("sellerAsTakerProtocol exist with tradeID " + tradeId);
                    }
                    if (offererAsBuyerProtocolMap.containsKey(tradeId)) {
                        offererAsBuyerProtocolMap.get(tradeId).setMailboxMessage(mailboxMessage);
                        log.trace("buyerAcceptsOfferProtocol exist with tradeID " + tradeId);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        }
        log.trace("mailboxMessages.size=" + mailboxMessages.size());
    }

    private void emptyMailbox() {
        mailboxService.removeAllMessages(user.getP2PSigPubKey(),
                () -> {
                    log.debug("All mailbox entries removed");
                },
                (errorMessage, fault) -> {
                    log.error(errorMessage);
                    log.error(fault.getMessage());
                });
    }

    boolean isOfferOpen(String offerId) {
        return openOfferTrades.containsKey(offerId)
                && (openOfferTrades.get(offerId).getOffer().getState() == Offer.State.UNKNOWN
                || openOfferTrades.get(offerId).getOffer().getState() == Offer.State.AVAILABLE);
    }

    private void persistOpenOffers() {
        persistence.write(this, "openOfferTrades", (Map<String, Trade>) new HashMap<>(openOfferTrades));
    }

    private void persistPendingTrades() {
        persistence.write(this, "pendingTrades", (Map<String, Trade>) new HashMap<>(pendingTrades));
    }

    private void persistClosedTrades() {
        persistence.write(this, "closedTrades", (Map<String, Trade>) new HashMap<>(closedTrades));
    }
}