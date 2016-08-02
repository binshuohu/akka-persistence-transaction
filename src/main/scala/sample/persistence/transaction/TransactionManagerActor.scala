package sample.persistence.transaction

import akka.actor.{ActorLogging, Props}
import akka.persistence.AtLeastOnceDelivery.AtLeastOnceDeliverySnapshot
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, SnapshotOffer}
import sample.persistence.account.AccountActor
import sample.persistence.domain.Account

case class TransferMoney(transactionId: Long, from: Account, to: Account, amount: Long)

sealed trait Event

case class TransactionInitiated(transactionId: Long, from: Account, to: Account, amount: Long) extends Event

case class MoneyFrozen(deliveryId: Long) extends Event

case class FreezingMoneyFailed(deliveryId: Long, reason: String) extends Event

case class MoneyAdded(deliveryId: Long) extends Event

case class AddingMoneyFailed(deliveryId: Long, reason: String) extends Event

case class TransactionFinished(deliveryId: Long) extends Event

case class MoneyUnfrozen(deliveryId: Long) extends Event

object TransactionManagerActor {
  def props(): Props = Props(classOf[TransactionManagerActor])
}

class TransactionManagerActor
  extends PersistentActor with AtLeastOnceDelivery with ActorLogging {

  override val persistenceId: String = self.path.name

  case class TrxnMgrState(transactionId: Long = -1L,
                          from: Account = "",
                          to: Account = "",
                          amount: Long = 0L,
                          failureReason: String = "",
                          deliverySnapshot: AtLeastOnceDeliverySnapshot = getDeliverySnapshot) {

    def updated(event: Event): TrxnMgrState = event match {
      case TransactionInitiated(trxdId, from, to, amount) =>
        TrxnMgrState(trxdId, from, to, amount, "", getDeliverySnapshot)

      case FreezingMoneyFailed(_, reason) => copy(failureReason = reason, deliverySnapshot = getDeliverySnapshot)

      case AddingMoneyFailed(_, reason) => copy(failureReason = reason, deliverySnapshot = getDeliverySnapshot)

      case _ => copy(deliverySnapshot = getDeliverySnapshot)
    }
  }

  var state = TrxnMgrState()

  def updateState(event: Event): Unit = {
    def fromActor = context.actorSelection(s"/user/${state.from}")
    def toActor = context.actorSelection(s"/user/${state.to}")

    state = state.updated(event)
    event match {
      case TransactionInitiated(id, from, to, amount) =>
        deliver(fromActor) { deliveryId =>
          AccountActor.FreezeMoney(deliveryId, id, to, amount)
        }

      case MoneyFrozen(deliveryId) =>
        confirmDelivery(deliveryId)
        deliver(toActor) { deliveryId =>
          AccountActor.AddMoney(deliveryId, state.transactionId, state.from, state.amount)
        }

      case FreezingMoneyFailed(deliveryId, reason) =>
        confirmDelivery(deliveryId)

      case MoneyAdded(deliveryId) =>
        confirmDelivery(deliveryId)
        deliver(fromActor) { deliveryId =>
          AccountActor.FinishTransaction(deliveryId, state.transactionId)
        }

      case AddingMoneyFailed(deliveryId, reason) =>
        confirmDelivery(deliveryId)
        deliver(fromActor) { deliveryId =>
          AccountActor.UnfreezeMoney(deliveryId, state.transactionId)
        }

      case MoneyUnfrozen(deliveryId) =>
        confirmDelivery(deliveryId)

      case TransactionFinished(deliveryId) =>
        confirmDelivery(deliveryId)
    }
  }

  val receiveRecover: Receive = {
    case evt: Event =>
      log.info(s"replay event: $evt")
      updateState(evt)

    case SnapshotOffer(_, snapshot: TrxnMgrState) =>
      state = snapshot
      setDeliverySnapshot(state.deliverySnapshot)
  }

  override def receiveCommand: Receive = {
    case TransferMoney(id, from, to, amount) =>
      persist(TransactionInitiated(id, from, to, amount))(updateState)

    case AccountActor.ConfirmMoneyFrozenFail(deliveryId, reason) =>
      persist(FreezingMoneyFailed(deliveryId, reason)) { e =>
        updateState(e)
        log.info(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")
        context.system.eventStream.publish(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")
      }

    case AccountActor.ConfirmMoneyFrozenSucc(deliveryId) =>
      persist(MoneyFrozen(deliveryId))(updateState)

    case AccountActor.ConfirmMoneyAddedFail(deliveryId, reason) =>
      persist(AddingMoneyFailed(deliveryId, reason))(updateState)

    case AccountActor.ConfirmMoneyAddedSucc(deliveryId) =>
      persist(MoneyAdded(deliveryId))(updateState)

    case AccountActor.ConfirmTransactionFinished(deliveryId) =>
      persist(TransactionFinished(deliveryId)) { e=>
        updateState(e)
        log.info(s"transaction ${state.transactionId} finished successfully")
        context.system.eventStream.publish(s"transaction ${state.transactionId} finished successfully")
      }

    case AccountActor.ConfirmMoneyUnfrozen(deliveryId) =>
      persist(MoneyUnfrozen(deliveryId)) { e =>
        updateState(e)
        log.info(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")
        context.system.eventStream.publish(s"unable to finish transaction ${state.transactionId}, reason: ${state.failureReason}")
      }
  }
}
