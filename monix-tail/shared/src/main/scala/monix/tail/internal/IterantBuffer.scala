/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.tail
package internal

import cats.effect.Sync
import cats.syntax.all._
import monix.execution.internal.collection.ArrayStack

import scala.util.control.NonFatal
import monix.tail.Iterant.{Concat, Halt, Last, Next, NextBatch, NextCursor, Scope, Suspend}
import monix.tail.batches.Batch

private[tail] object IterantBuffer {
  /** Implementation for `Iterant.bufferSliding`. */
  def sliding[F[_], A](self: Iterant[F, A], count: Int, skip: Int)
    (implicit F: Sync[F]): Iterant[F, Seq[A]] = {

    build[F, A, Seq[A]](self, count, skip,
      (seq, rest) => Next(seq, rest),
      seq => Last(seq))
  }

  /** Implementation for `Iterant.batched`. */
  def batched[F[_], A](self: Iterant[F, A], count: Int)
    (implicit F: Sync[F]): Iterant[F, A] = {

    build[F, A, A](self, count, count,
      (seq, rest) => NextBatch(Batch.fromArray(seq), rest),
      seq => NextBatch(Batch.fromArray(seq), F.pure(Iterant.empty)))
  }

  private def build[F[_], A, B](
    self: Iterant[F, A],
    count: Int,
    skip: Int,
    f: (Array[A], F[Iterant[F, B]]) => Iterant[F, B],
    last: Array[A] => Iterant[F, B])
    (implicit F: Sync[F]): Iterant[F, B] = {

    Suspend(F.suspend(new Loop(count, skip, f, last).apply(self)))
  }

  private class Loop[F[_], A, B](
    count: Int,
    skip: Int,
    f: (Array[A], F[Iterant[F, B]]) => Iterant[F, B],
    last: Array[A] => Iterant[F, B]
  )(implicit F: Sync[F])
  extends (Iterant[F, A] => F[Iterant[F, B]]) {
    private[this] val buffer = new Buffer[A](count, skip)
    private[this] val stack  = new ArrayStack[F[Iterant[F, A]]]()

    def process(fa: Iterant.NextCursor[F, A]): F[Iterant[F, B]] = {
      val NextCursor(cursor, rest) = fa

      while (cursor.hasNext()) {
        val seq = buffer.push(cursor.next())
        if (seq != null) {
          val next = if (cursor.hasNext()) F.pure(fa: Iterant[F, A]) else rest
          return F.pure(f(seq, next.flatMap(this)))
        }
      }

      rest.flatMap(this)
    }

    def apply(self: Iterant[F, A]): F[Iterant[F, B]] = {
      try self match {
        case Next(a, rest) =>
          val seq = buffer.push(a)
          if (seq != null)
            F.pure(f(seq, rest.flatMap(this)))
          else
            rest.flatMap(this)

        case self@NextCursor(_, _) =>
          process(self)

        case NextBatch(batch, rest) =>
          process(NextCursor(batch.cursor(), rest))

        case Suspend(rest) =>
          rest.flatMap(this)

        case Last(a) if stack.isEmpty =>
          var seq = buffer.push(a)
          if (seq == null) seq = buffer.rest()
          F.pure {
            if (seq != null && seq.length > 0)
              last(seq)
            else
              Iterant.empty
          }

        case Last(a) =>
          val seq = buffer.push(a)
          val rest = stack.pop()
          if (seq != null) {
            F.pure(f(seq, rest.flatMap(this)))
          } else {
            rest.flatMap(this)
          }

        case ref @ Halt(_) if stack.isEmpty || ref.e.isDefined =>
          val self = ref.asInstanceOf[Iterant[F, B]]
          val seq = buffer.rest()
          F.pure {
            if (seq != null && seq.length > 0)
              f(seq, F.pure(self))
            else
              self
          }

        case Halt(_) =>
          stack.pop().flatMap(this)

        case s @ Scope(_, _, _) =>
          s.runFlatMap(this)

        case Concat(lh, rh) =>
          stack.push(rh)
          lh.flatMap(this)
      }
      catch {
        case e if NonFatal(e) =>
          F.pure(Iterant.raiseError(e))
      }
    }
  }

  private final class Buffer[A](count: Int, skip: Int) {
    private[this] val toDrop = if (count > skip) 0 else skip - count
    private[this] val toRepeat = if (skip > count) 0 else count - skip

    private[this] var isBufferNew = true
    private[this] var buffer = new Array[AnyRef](count)
    private[this] var dropped = 0
    private[this] var length = 0

    def push(elem: A): Array[A] = {
      if (dropped > 0) {
        dropped -= 1
        null
      } else {
        buffer(length) = elem.asInstanceOf[AnyRef]
        length += 1

        if (length < count) null else {
          val oldBuffer = buffer
          buffer = new Array(count)

          if (toRepeat > 0) {
            System.arraycopy(oldBuffer, count-toRepeat, buffer, 0, toRepeat)
            length = toRepeat
          } else {
            dropped = toDrop
            length = 0
          }

          // signaling downstream
          if (isBufferNew) isBufferNew = false
          oldBuffer.asInstanceOf[Array[A]]
        }
      }
    }

    def rest(): Array[A] = {
      val threshold = if (isBufferNew) 0 else toRepeat
      if (length > threshold)
        buffer.take(length).asInstanceOf[Array[A]]
      else
        null
    }
  }
}
