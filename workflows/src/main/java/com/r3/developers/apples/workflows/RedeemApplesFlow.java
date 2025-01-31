package com.r3.developers.apples.workflows;

import com.r3.developers.apples.contracts.AppleCommands;
import com.r3.developers.apples.states.AppleStamp;
import com.r3.developers.apples.states.BasketOfApples;
import net.corda.v5.application.flows.ClientRequestBody;
import net.corda.v5.application.flows.ClientStartableFlow;
import net.corda.v5.application.flows.CordaInject;
import net.corda.v5.application.flows.InitiatingFlow;
import net.corda.v5.application.marshalling.JsonMarshallingService;
import net.corda.v5.application.membership.MemberLookup;
import net.corda.v5.application.messaging.FlowMessaging;
import net.corda.v5.application.messaging.FlowSession;
import net.corda.v5.base.annotations.Suspendable;
import net.corda.v5.base.types.MemberX500Name;
import net.corda.v5.ledger.common.NotaryLookup;
import net.corda.v5.ledger.utxo.StateAndRef;
import net.corda.v5.ledger.utxo.UtxoLedgerService;
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction;
import net.corda.v5.membership.NotaryInfo;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@InitiatingFlow(protocol = "redeem-apples")
public class RedeemApplesFlow implements ClientStartableFlow {

    @CordaInject
    FlowMessaging flowMessaging;

    @CordaInject
    JsonMarshallingService jsonMarshallingService;

    @CordaInject
    MemberLookup memberLookup;

    @CordaInject
    NotaryLookup notaryLookup;

    @CordaInject
    UtxoLedgerService utxoLedgerService;

    public RedeemApplesFlow() {}

    @Suspendable
    @Override
    public String call(ClientRequestBody requestBody) {

        RedeemApplesRequest request = requestBody.getRequestBodyAs(jsonMarshallingService, RedeemApplesRequest.class);
        MemberX500Name buyerName = request.getBuyer();
        UUID stampId = request.getStampId();

        // Retrieve the notaries public key (this will change)
        NotaryInfo notaryInfo = notaryLookup.getNotaryServices().iterator().next();

        PublicKey myKey = memberLookup.myInfo().getLedgerKeys().get(0);

        PublicKey buyer;
        try {
            buyer = memberLookup.lookup(buyerName).getLedgerKeys().get(0);
        } catch (Exception e) {
            throw new IllegalArgumentException("The buyer does not exist within the network");
        }

        StateAndRef<AppleStamp> appleStampStateAndRef;
        try {
            appleStampStateAndRef = utxoLedgerService
                    .findUnconsumedStatesByType(AppleStamp.class)
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getContractState().getId().equals(stampId))
                    .iterator()
                    .next();
        } catch (Exception e) {
            throw new IllegalArgumentException("There are no eligible basket of apples");
        }

        StateAndRef<BasketOfApples> basketOfApplesStampStateAndRef;
        try {
            basketOfApplesStampStateAndRef = utxoLedgerService
                    .findUnconsumedStatesByType(BasketOfApples.class)
                    .stream()
                    .filter(
                            stateAndRef -> stateAndRef.getState().getContractState().getOwner().equals(
                                    appleStampStateAndRef.getState().getContractState().getIssuer()
                            )
                    )
                    .iterator()
                    .next();
        } catch (Exception e) {
            throw new IllegalArgumentException("There are no eligible baskets of apples");
        }

        BasketOfApples originalBasketOfApples = basketOfApplesStampStateAndRef.getState().getContractState();

        BasketOfApples updatedBasket = originalBasketOfApples.changeOwner(buyer);

        //Create the transaction
        UtxoSignedTransaction transaction = utxoLedgerService.createTransactionBuilder()
                .setNotary(notaryInfo.getName())
                .addInputStates(appleStampStateAndRef.getRef(), basketOfApplesStampStateAndRef.getRef())
                .addOutputState(updatedBasket)
                .addCommand(new AppleCommands.Redeem())
                .setTimeWindowUntil(Instant.now().plus(1, ChronoUnit.DAYS))
                .addSignatories(List.of(myKey, buyer))
                .toSignedTransaction();

        FlowSession session = flowMessaging.initiateFlow(buyerName);

        try {
            // Send the transaction and state to the counterparty and let them sign it
            // Then notarise and record the transaction in both parties' vaults.
            return utxoLedgerService.finalize(transaction, List.of(session)).toString();
        } catch (Exception e) {
            return String.format("Flow failed, message: %s", e.getMessage());
        }
    }
}