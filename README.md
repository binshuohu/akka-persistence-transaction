### This is a simple demonstration on how to use facilities provided by akka persistence to implement an eventually consistent transaction in a distributed setting

As explained in the paper [Life Beyond Distributed Transaction](http://adrianmarriott.net/logosroot/papers/LifeBeyondTxns.pdf), if we want our systems to scale,
we should model entities(which is also called aggregate in Domain Driven Design term) in a way that we can perform almost all business operations only by touching a single entity,
which resides on a single machine so we don't have to pay for the costly nodes coordination.

With this kind of philosophy in mind, when we model our entities, we must consider each entity as a transactional boundary and design different operations that could be performed on them.

All of these sound very reasonable and doable, but sooner or later we may run into a scenario where the business need require us to modify multiple entities to do the job.
So what do we do about it?

A brutal solution would be "Let's aggregate all entities into a single entities".
Well, maybe it's feasible for some cases, but in general this solution is much like the traditional database oriented solution in which all the dirty work is delegated to the persistence layer.
We definitely can do better than that.

OK, so how do we go about it? In the aforementioned paper, the author came up with a concept called *activity*.
It's also known as *Process Manager Pattern* or *Saga Pattern*.
This pattern is quite similar to how people cooperate to get things done in real world.
Basically, you have a *process manager* that orchestrate the whole transaction, telling each entity involved to perform the required operation step by step.
And in the case of one or more parties could not proceed, such as illegal operation due to unmet precondition,
the process manager knows how to instruct others to unwind the work has already been done for this transaction, thus restoring the system to a consistent state.

The demo implements a process manager represented by the class [`TransactionManagerActor`](src/main/scala/sample/persistence/transaction/TransactionManagerActor.scala).
The account involved in the money transfer is represented by the class [`AccountActor`](src/main/scala/sample/persistence/account/AccountActor.scala).

If we want to transfer M bucks from account A to account B:

1. Create a process manager instance, let's call it `pm`, then send it a command to begin the transaction.

2. `pm` receives the command and turns it into an event called `TransactionInitiated` and persists this event like any other following event.

3. `pm` sends out a command `FreezeMoney` to account `A`.

4. `A` receives the command, validates it before proceeding. There are 2 possible branches after the validation.

5. - a. If `A` doesn't have sufficient balance to make the transaction,
it will send back an ack to `pm` to indicate the error, then `pm` should notify whoever interested in this transaction that it failed, after which the whole process is done for good.

   - b. If `A` has enough money to make the deal, it will persist `MoneyFrozen` event, update its state to add an in flight transaction and deduct M bucks from its available balance before send back an ack to `pm`

6. When `pm` receives `A`'s ack, it will also persist an `MoneyFrozen` event to mark the progress, then sends an `AddMoney` command to Account `B`.

7. When `B` receives `AddMoney` command, it will first validate it, check whether this account is active so that it can do money transaction.
    - a. If `B` is active, it will persist event `MoneyAdded`, update state before sending ack to `pm`, then the process goes to step 8
    - b. If `B` is inactive, it will just send back an ack to `pm` to indicate this. Then goes to step 11.

8. `pm` receives ack from `B`, persists `MoneyAdded` event and sends command `FinishTransaction` to `A`.

9. `A` receives this command then persists event `TransactionFinished` and updates its state to mark the in flight transaction as a finished transaction. Then, like before, it sends ack to `pm`

10. Now `pm` knows the transaction finished, it will persist `TransactionFinished` and notify related party about the result.

11. `pm` now understands `B` couldn't make the deal because it's inactive, it will persist this fact and then send to account `A` an `UnFreezeMoney` command to compensate the transaction.

12. When `A` receives this command, it will persist `MoneyUnfrozen` and unwind its state to delete the in flight transaction and restore M bucks to its available balance. Then, as always, it sends ack to `pm`.

13. After having received ack from `A`, `pm` will persist `MoneyUnfrozen` and notify downstream about the abortion of the transaction.


It takes a lot of words to explain, but the work flow is pretty simple. People who work on banking systems will definitely say:"That's not the real work flow of transaction".
Please don't get mad. I'm not quite sure how real banking system do money transfer, but I think the demo application has grasped the gist of using compensation to make the system's state eventually consistent.

There are a few things to watch out when implementing this pattern using akka-persistence:

##### 1. Use at-least-once message delivery semantic with idempotent message receiver.

  The world is not perfect, we can't always get exact-once message delivery, neither can we use at-most-once message delivery semantic if we want data consistency.
  The option left is obivous.
  
  akka-persistence provides us wi [`AtLeastOnceDelivery`](http://doc.akka.io/docs/akka/2.4/scala/persistence.html#At-Least-Once_Delivery) to achieve this.
  One thing to notice here is that `AtLeastOnceDelivery` trait its self also maintains a state to track unconfirmed messages. When we make snapshot, we must include this state as part of the actor state, using `getDeliverySnapshot` method and restore it by calling `setDeliverySnapshot` when recovering from snapshot.

##### 2. Watch out where to put side effect.

  When the actor reacts to an event, we must be careful how we perform side effect. Because events could come either from transforming an command or during recovering.
  
  For example, when `pm` reacts to `TransactionFinished` event, whether it should notify downstream about this every time, even in the case of recovering?
  In this demo, I chose not to do this, so I put this "notifying" side effect in the `receiveCommand` method.
  Why? I think down stream shouldn't be notified if `pm` recovers from disk. Whoever cares about the result of the transaction, if it didn't receive the notification the first time, maybe it can actively query against `pm` to get the result.
  I don't think there's one simple rule to follow. We always need to first figure out how our persistent actor should interact with the outside world when we make this decision.