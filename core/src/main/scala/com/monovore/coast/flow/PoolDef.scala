package com.monovore.coast
package flow

import com.monovore.coast.model._
import com.monovore.coast.wire.BinaryFormat

class PoolDef[+G <: AnyGrouping, A, +B](
  private[coast] val initial: B,
  private[coast] val element: Node[A, B]
) { self =>

  def updates: StreamDef[G, A, B] = new StreamDef(element)

  def updatedPairs[B0 >: B](
    implicit isGrouped: IsGrouped[G], keyFormat: BinaryFormat[A], valueFormat: BinaryFormat[B0]
  ): GroupedStream[A, (B0, B0)] =
    updates.transform(initial: B0) { (last, current) =>
      current -> Seq(last -> current)
    }

  def map[B0](function: B => B0): PoolDef[G, A, B0] =
    updates.map(function).latestOr(function(initial))

  def join[B0 >: B, B1](other: GroupedPool[A, B1])(
    implicit isGrouped: IsGrouped[G], keyFormat: BinaryFormat[A], pairFormat: BinaryFormat[(B0, B1)]
  ): PoolDef[Grouped, A, (B0, B1)] = {

    val merged = Flow.merge(
      "left" -> isGrouped.pool(this).updates.map(Left(_)),
      "right" -> other.updates.map(Right(_))
    )

    merged
      .fold(initial: B0, other.initial) { (state, update) =>
        update.fold(
          { left => (left, state._2) },
          { right => (state._1, right) }
        )
      }
  }
}
