package sample.persistence.transaction

import akka.persistence.{AtLeastOnceDelivery, PersistentActor, SnapshotOffer}
import sample.persistence.account.AccountActor
import sample.persistence.domain.Account

case class TransactMoney(transactionId: Long, from: Account, to: Account, amount: Long)

sealed trait Event

case class TransactionInitiated(transactionId: Long, from: Account, to: Account, amount: Long) extends Event

case object MoneyFrozen extends Event

case class FreezingMoneyFailed(reason: String) extends Event

case object MoneyAdded extends Event

case class AddingMoneyFailed(reason: String) extends Event

case object TransactionFinished extends Event

case object MoneyUnfrozen extends Event

case class TrxnMgrState(transactionId: Long = -1L,
                        from: Account = "",
                        to: Account = "",
                        amount: Long = 0L,
                        failureReason: String = "") {

  def updated(event: Event): TrxnMgrState = event match {
    case TransactionInitiated(trxdId, from, to, amount) =>
      TrxnMgrState(trxdId, from, to, amount, "")

    case FreezingMoneyFailed(reason) => copy(failureReason = reason)

    case AddingMoneyFailed(reason) => copy(failureReason = reason)

    case _ => this
  }
}

class TransactionManagerActor extends PersistentActor with AtLeastOnceDelivery {
  override val persistenceId: String = "transaction-manager-42"

  var state = TrxnMgrState()

  def updateState(event: Event): Unit = {
    def fromActor = context.actorSelection(s"/user/accounts/${state.from}")
    def toActor = context.actorSelection(s"user/accounts/${state.to}")

    state = state.updated(event)
    event match {
      case TransactionInitiated(id, from, to, amount) =>
        deliver(fromActor) { deliveryId =>
          AccountActor.FreezeMoney(deliveryId, id, to, amount)
        }

      case MoneyFrozen =>
        deliver(toActor) { deliveryId =>
          AccountActor.AddMoney(deliveryId, state.transactionId, state.from, state.amount)
        }

      case FreezingMoneyFailed(reason) =>
        context.system.eventStream.publish(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")

      case MoneyAdded =>
        deliver(fromActor) { deliveryId =>
          AccountActor.FinishTransaction(deliveryId, state.transactionId)
        }

      case AddingMoneyFailed(reason) =>
        deliver(fromActor) { deliveryId =>
          AccountActor.UnfreezeMoney(deliveryId, state.transactionId)
        }

      case MoneyUnfrozen =>
        context.system.eventStream.publish(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")


      case TransactionFinished =>
        context.system.eventStream.publish(s"transaction ${state.transactionId} finished successfully")
    }
  }


  val receiveRecover: Receive = {
    case evt: Event => updateState(evt)
    case SnapshotOffer(_, snapshot: TrxnMgrState) => state = snapshot
  }

  override def receiveCommand: Receive = {
    case TransactMoney(id, from, to, amount) =>
      persist(TransactionInitiated(id, from, to, amount)) { e =>
        updateState(e)
      }

    case AccountActor.ConfirmMoneyFrozenFail(deliveryId, reason) =>
      persist(FreezingMoneyFailed(reason)) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }


    case AccountActor.ConfirmMoneyFrozenSucc(deliveryId) =>
      persist(MoneyFrozen) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }

    case AccountActor.ConfirmMoneyAddedFail(deliveryId, reason) =>
      persist(AddingMoneyFailed(reason)) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }

    case AccountActor.ConfirmMoneyAddedSucc(deliveryId) =>
      persist(MoneyAdded) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }

    case AccountActor.ConfirmTransactionFinished(deliveryId) =>
      persist(TransactionFinished) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }

    case AccountActor.ConfirmMoneyUnfrozen(deliveryId) =>
      persist(MoneyUnfrozen) { e =>
        confirmDelivery(deliveryId)
        updateState(e)
      }
  }

  override def preStart(): Unit = {
    super.preStart()
    self ! TransactMoney(123456789L, "Clark", "Bruce", 300)
  }
}
