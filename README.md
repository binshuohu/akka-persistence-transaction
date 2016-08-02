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