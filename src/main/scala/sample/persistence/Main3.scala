package sample.persistence

import akka.actor.ActorSystem
import sample.persistence.account.AccountActor
import sample.persistence.transaction.{TransactionManagerActor, TransferMoney}

//demonstrate compensation in case of receiver account is inactive
object Main3 extends App {
  val system = ActorSystem("transaction")
  val barry = system.actorOf(AccountActor.props(active = true, balance = 500), "Barry")
  val victor = system.actorOf(AccountActor.props(active = false, balance = 10000), "Victor")

  val tm = system.actorOf(TransactionManagerActor.props(), "tm3")

  barry ! "print"
  victor ! "print"

  tm ! TransferMoney(123456L, "Barry", "Victor", 100L)

  Thread.sleep(5000)
  barry ! "print"
  victor ! "print"

}
