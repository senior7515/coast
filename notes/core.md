
Notes
==

Sharing
===

A 'pure' DAG and a tree are indistinguishable, modulo object identity. For
example, there's no way to distinguish between the following two situations:

```scala
val one = ints.map { _ + 1 } merge ints.map { _ + 1 }

val tmp = ints.map { _ + 1 }
val two = tmp merge tmp
```

This is resolved by labelling the nodes we want shared. The implementation is
responsible for ensuring that labels are unique.

```scala
for {
  tmp <- register("incremented") {
    ints.map { _ + 1 }
  }
} yield tmp merge tmp
```


Grouping
===

As a 'shuffle' operation, regrouping streams is expensive.

Doing the grouping on the downstream end would involve reading all partitions,
so this needs to happen upstream. Without reading the downstream state, though,
it can't ensure that it's written each element to the stream exactly once.

In a distributed flow, regrouping is also the only operation that forces
messages across the wire. This implies that we can get away with only requiring
labels for regrouped streams. (Of course, more labels can be added to taste.)
The label doesn't really need to be applied right away -- but it needs to be there
before any accumulating happens.

This can be managed by tracking the 'regrouped' status in the types. In the same
way Finagle does the ServiceBuilder et al, `coast` can set a type-level flag when
grouping happens and use it to control which options are available.

Sinks
===

A 'sink' is an exported queue, and as such, we'd like to ensure that:
- our writes to it are exactly-once
- it contains no 'metadata', just the keys and values we intend

This is a bit tricky! Here's the best I have so far:

Assume the incoming stream is deterministic. We can look at the output stream, and
see what the latest offset we wrote is. We then replay the input stream from the
beginning. For every message in the output, we drop and increment until we reach the
last point that we can find downstream; we then continue to write to the stream as
normal.

If the input *isn't* deterministic, it seems the best `coast` can do is at-least-once, Samza
style. For now, we can assume that all the nondeterminism is from grouping and merges, and
stick this in the types as well.

State
===

State is hard!

For every (input, intermediate, final) stream, we can calculate the 'offset', or
distance from the beginning. We need this to get exactly-once semantics for
inputs and state. We probably don't want to reify this unless we need it, since
it could interfere with some refactorings.

(This is a good argument for separating stateful and stateless transformations,
incidentally.)

'Essential' state is the data necessary to fully determine the 'value' of the
stream. All input is essential; some intermediate results are as well, iff they
couldn't be recomputed. For example, when merging streams the order is essential
state, since we need to be able to reproduce it on demand.

'Incedental' state is not strictly necessary -- it's kept around to avoid huge
amounts of repeated work. The most obvious is source offsets: we could
theoretically replay the input data from scratch every time, though we may not
want to. Same deal with the state in a fold / etc. Changing the frequency / etc.
at which this is recorded shouldn't change the results. OHOH, this data needs to
be always consistent. In particular, the downstream state must always be at
least as recent as the upstream state... we can always skip repeated values, but
we can't spirit up missing ones.

One way to ensure this is to stick these state changes into the data stream.
This avoids a bunch of concurrency / visibility issues.

This sounds like a two-step-type process... we do all the wiring, everybody
reads checkpoints from their downstream until they get some sigil value, then
starts their regular processing.

Merges are interesting, since they involve both some essential state (the
selection order) and some checkpointy stuff (how much has been emitted so far).
Seems like it should be doable as well, though.

Names for State
===

A 'name' is public, and should be usable from multiple flows. For streams, this
is straightforward -- all the data is published on Kafka or whatever. For
pools, this gets trickier, because there's an initial state as well. Sticking that
to the name would be weird and make folds awkward.

Probably should only allow folks to subscribe to streams, and keep pools internal.
Should save a lot of heartache.

There's a bunch of state implicit in all deployed topologies -- for example, every
source needs to track how far along it is in the Kafka topic. All this state needs
to live somewhere; ideally, it should be migratable when the flow structure changes.

Some possibilities:

- Handle this purely (or primarily) structurally: all the state implicit in the AST
is flattened out into 'one big state' and stored together. This requires no labelling,
since we can always refer to the individual bits by their position in the AST. On the
other hand, it's very sensitive to small refactorings -- changes as minimal as adding
another spout will change the references, and craziness may result.

- Require that every bit of state get a state-label, which is unique per-process. This
is syntactially heavyweight, and is more likely to fail at runtime. (Global names are
a necessary evil for streams and processes, but they have a cost -- a name collision
can happen anywhere, so checking is nonlocal.)

- A hybrid JSON-style nesting: individual merge / etc. statements have a k/v mapping,
and individual pieces of state can be referred to by the path down through the maps.
This makes adding and removing keys free, but changing the overall structure or
redefining keys is still janky.

Machine Tests
===

What are you trying to prove?

Flows are equivalent when they publish the same streams, and every possible output
from one is also a possible output from the other.

When testing laws, we want to declare that two flows are exactly equivalent. In
theory, this involves generating all possible outputs for each and testing that
they're identical; in practice we can probably get away with generating a bunch of
random runs through the first one and testing that it's possible to get the same
output from the second. This involves a backtracking-type search, but in the normal
case it should be much more efficient?

When testing backends, we want to assert that the output is a possible output of
the model. This can use the same backtracking-type search above.

When testing flows, we want to assert some high-level property of the model.
Generating random runs and checking the property seems to be the thing here as well.

So a) generate random runs and b) check if an output is possible, returning either a
valid trace or an error.

Principles
===

- Reasonable: the API should expose primitives that simplify both formal and
  informal reasoning.

- Testable: it should be easy to write tests that check properties about the
  defined flow, without having to spin up a full distributed system.

- Efficient: Users should be able to write flows that are roughly as efficient
  as a handwritten version. In particular, the library should not force the
  user to keep unnecessary state or trips over the network.

- Transparent: It should be simple to see the mapping between the user's flow
  and the compiled version, at least in a black-box way.

Blockers
===

- Minimal required labelling
- Actual Backend

Pull vs. Push
===

It's hard to put a time-cap on 'pull', since you may need to consume an
arbitrary amount of input before producing any output. On the other hand,
if you care about the order in which you get your input,
you need *some* way to control where your input comes from.

This is doubly important for merge nodes, which go through the following life-
cycle:

- State is restored from a checkpoint, including:
  - The 'high-water mark', which is the largest offset in the log
    that was confirmed downstream. Given the data in the log, this implies a
    high-water mark for the incoming streams as well. (Checkpoint this?)
  - The 'current' offset for all incoming streams, as regular checkpoints.

- Start the incoming streams, dropping input until their individual high-water
  marks.

- From our high-water mark, continue replaying until the log is exhausted

- Start taking arbitrary messages from upstream, logging our choices

This is a complicated flow, and involves both 'pulling' and 'pushing' data.
'Just' push might be acceptable, as long as there's a mechanism for backpressure;
for example, a disruptor-queue-based implementation would probably be pretty
straightforward.

Thinking about a Samza- or Storm-backed implementation, there's another related
issue. The spout or input chooser or whatever needs to replay the sources after
a failure. Consider:

```scala
Flow.merge(numbers.map { 1 to _ }, other)
  .doWhatever
```

If `coast` logs the merge choices exactly where the merge happens, there's no
1-1 correspondence between the offset in numbers and the offset of the input
that's fed to the merge. If the spout is feeding input in one at a time, there's
almost certainly going to need to be some internal buffering in the merge
implementation, which is lame. There's no upper bound to how much buffering
needs to happen. The disruptor-based implementation would handle this just fine.

An alternative situation is to 'pull' all the merges to the top. The spout /
thing could handle all the replay and selection itself... but this implies that
the implementation would have to completely process each element before starting
on the next one. This is an admissable implementation, but it's a bit weird that
it can't represent as many interleavings as a more general version could. The
only time I can imagine this causing trouble is if: each message from one source
could produce a large number of intermediate messages, and we have a second
source which we'd always like to handle promptly. BUT that's probably not a huge
problem?

So I'm relatively convinced: it's not worth the pain of trying to have one
core model for both a 'native' and 'spouty' implementation, since they have a
different structure and keep different state. Of course, this just shifts things
to a different pain of having two implementations: but hey, life is suffering.

So, a different implementation:

Replay the checkpoint stream. The 'spout' tracks the current offset in each
source; the 'bolt' applies all the state updates or whatever. Then, replay the
merge stream, taking input from the appropriate upstream and sending it out.
Then, just send whatever and remember what you pick.
