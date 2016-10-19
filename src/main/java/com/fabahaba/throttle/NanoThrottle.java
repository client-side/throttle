/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fabahaba.throttle;

import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * {@inheritDoc}
 */
abstract class NanoThrottle implements Throttle {

  /*
 * How is the RateLimiter designed, and why?
 *
 * The primary feature of a RateLimiter is its "stable rate", the maximum rate that is should
 * allow at normal conditions. This is enforced by "throttling" incoming requests as needed, i.e.
 * compute, for an incoming request, the appropriate throttle time, and make the calling thread
 * wait as much.
 *
 * The simplest way to maintain a rate of QPS is to keep the timestamp of the last granted
 * request, and ensure that (1/QPS) seconds have elapsed since then. For example, for a rate of
 * QPS=5 (5 tokens per second), if we ensure that a request isn't granted earlier than 200ms after
 * the last one, then we achieve the intended rate. If a request comes and the last request was
 * granted only 100ms ago, then we wait for another 100ms. At this rate, serving 15 fresh permits
 * (i.e. for an acquire(15) request) naturally takes 3 seconds.
 *
 * It is important to realize that such a RateLimiter has a very superficial memory of the past:
 * it only remembers the last request. What if the RateLimiter was unused for a long period of
 * time, then a request arrived and was immediately granted? This RateLimiter would immediately
 * forget about that past under-utilization. This may result in either under-utilization or
 * overflow, depending on the real world consequences of not using the expected rate.
 *
 * Past under-utilization could mean that excess resources are available. Then, the RateLimiter
 * should speed up for a while, to take advantage of these resources. This is important when the
 * rate is applied to networking (limiting bandwidth), where past under-utilization typically
 * translates to "almost empty buffers", which can be filled immediately.
 *
 * On the other hand, past under-utilization could mean that "the server responsible for handling
 * the request has become less ready for future requests", i.e. its caches become stale, and
 * requests become more likely to trigger expensive operations (a more extreme case of this
 * example is when a server has just booted, and it is mostly busy with getting itself up to
 * speed).
 *
 * To deal with such scenarios, we add an extra dimension, that of "past under-utilization",
 * modeled by "storedPermits" variable. This variable is zero when there is no under-utilization,
 * and it can grow up to maxStoredPermits, for sufficiently large under-utilization. So, the
 * requested permits, by an invocation acquire(permits), are served from:
 *
 * - stored permits (if available)
 *
 * - fresh permits (for any remaining permits)
 *
 * How this works is best explained with an example:
 *
 * For a RateLimiter that produces 1 token per second, every second that goes by with the
 * RateLimiter being unused, we increase storedPermits by 1. Say we leave the RateLimiter unused
 * for 10 seconds (i.e., we expected a request at time X, but we are at time X + 10 seconds before
 * a request actually arrives; this is also related to the point made in the last paragraph), thus
 * storedPermits becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of
 * acquire(3) arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how
 * this is translated to throttling time is discussed later). Immediately after, assume that an
 * acquire(10) request arriving. We serve the request partly from storedPermits, using all the
 * remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits produced by the
 * rate limiter.
 *
 * We already know how much time it takes to serve 3 fresh permits: if the rate is
 * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7 stored
 * permits? As explained above, there is no unique answer. If we are primarily interested to deal
 * with under-utilization, then we want stored permits to be given out /faster/ than fresh ones,
 * because under-utilization = free resources for the taking. If we are primarily interested to
 * deal with overflow, then stored permits could be given out /slower/ than fresh ones. Thus, we
 * require a (different in each case) function that translates storedPermits to throttling time.
 *
 * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake). The
 * underlying model is a continuous function mapping storedPermits (from 0.0 to maxStoredPermits)
 * onto the 1/rate (i.e. intervals) that is effective at the given storedPermits. "storedPermits"
 * essentially measure unused time; we spend unused time buying/storing permits. Rate is
 * "permits / time", thus "1 / rate = time / permits". Thus, "1/rate" (time / permits) times
 * "permits" gives time, i.e., integrals on this function (which is what storedPermitsToWaitTime()
 * computes) correspond to minimum intervals between subsequent requests, for the specified number
 * of requested permits.
 *
 * Here is an example of storedPermitsToWaitTime: If storedPermits == 10.0, and we want 3 permits,
 * we take them from storedPermits, reducing them to 7.0, and compute the throttling for these as
 * a call to storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
 * evaluate the integral of the function from 7.0 to 10.0.
 *
 * Using integrals guarantees that the effect of a single acquire(3) is equivalent to {
 * acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc, since the integral
 * of the function in [7.0, 10.0] is equivalent to the sum of the integrals of [7.0, 8.0], [8.0,
 * 9.0], [9.0, 10.0] (and so on), no matter what the function is. This guarantees that we handle
 * correctly requests of varying weight (permits), /no matter/ what the actual function is - so we
 * can tweak the latter freely. (The only requirement, obviously, is that we can compute its
 * integrals).
 *
 * Note well that if, for this function, we chose a horizontal line, at height of exactly (1/QPS),
 * then the effect of the function is non-existent: we serve storedPermits at exactly the same
 * cost as fresh ones (1/QPS is the cost for each). We use this trick later.
 *
 * If we pick a function that goes /below/ that horizontal line, it means that we reduce the area
 * of the function, thus time. Thus, the RateLimiter becomes /faster/ after a period of
 * under-utilization. If, on the other hand, we pick a function that goes /above/ that horizontal
 * line, then it means that the area (time) is increased, thus storedPermits are more costly than
 * fresh permits, thus the RateLimiter becomes /slower/ after a period of under-utilization.
 *
 * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
 * completely unused, and an expensive acquire(100) request comes. It would be nonsensical to just
 * wait for 100 seconds, and /then/ start the actual task. Why wait without doing anything? A much
 * better approach is to /allow/ the request right away (as if it was an acquire(1) request
 * instead), and postpone /subsequent/ requests as needed. In this version, we allow starting the
 * task immediately, and postpone by 100 seconds future requests, thus we allow for work to get
 * done in the meantime instead of waiting idly.
 *
 * This has important consequences: it means that the RateLimiter doesn't remember the time of the
 * _last_ request, but it remembers the (expected) time of the _next_ request. This also enables
 * us to tell immediately (see tryAcquire(timeout)) whether a particular timeout is enough to get
 * us to the point of the next scheduling time, since we always maintain that. And what we mean by
 * "an unused RateLimiter" is also defined by that notion: when we observe that the
 * "expected arrival time of the next request" is actually in the past, then the difference (now -
 * past) is the amount of time that the RateLimiter was formally unused, and it is that amount of
 * time which we translate to storedPermits. (We increase storedPermits with the amount of permits
 * that would have been produced in that idle time). So, if rate == 1 permit per second, and
 * arrivals come exactly one second after the previous, then storedPermits is _never_ increased --
 * we would only increase it for arrivals _later_ than the expected one second.
 */

  private static final double ONE_SECOND_NANOS = 1_000_000_000.0;

  private final long nanoStart;
  volatile double storedPermits;
  volatile double maxPermits;
  volatile double stableIntervalNanos;
  private volatile long nextFreeTicketNanos = 0L;
  private final Object mutex = new Object();

  private NanoThrottle(final double permitsPerSecond) {
    if (permitsPerSecond <= 0.0 || Double.isNaN(permitsPerSecond)) {
      throw new IllegalArgumentException("rate must be positive");
    }
    this.nanoStart = System.nanoTime();
    doSetRate(permitsPerSecond);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setRate(final double permitsPerSecond) {
    if (permitsPerSecond <= 0.0 || Double.isNaN(permitsPerSecond)) {
      throw new IllegalArgumentException("rate must be positive");
    }
    synchronized (mutex) {
      doSetRate(permitsPerSecond);
    }
  }

  private void doSetRate(final double permitsPerSecond) {
    reSync(System.nanoTime() - nanoStart);
    this.stableIntervalNanos = ONE_SECOND_NANOS / permitsPerSecond;
    doSetRate(permitsPerSecond, stableIntervalNanos);
  }

  abstract void doSetRate(final double permitsPerSecond, final double stableIntervalNanos);

  /**
   * {@inheritDoc}
   */
  @Override
  public final double getRate() {
    synchronized (mutex) {
      return doGetRate();
    }
  }

  private double doGetRate() {
    return ONE_SECOND_NANOS / stableIntervalNanos;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double acquire() {
    return acquire(1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final double acquire(final int permits) {
    final long nanosToWait = reserve(permits);
    sleepNanosUninterruptibly(nanosToWait);
    return nanosToWait / ONE_SECOND_NANOS;
  }

  /**
   * Reserves the given number of permits from this {@code NanoThrottle} for future use, returning
   * the number of nanoseconds until the reservation can be consumed.
   *
   * @return time in nanoseconds to wait until the resource can be acquired, never negative
   */
  private long reserve(final int permits) {
    checkPermits(permits);
    synchronized (mutex) {
      return reserveAndGetWaitLength(permits, System.nanoTime() - nanoStart);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean tryAcquire(final long timeout, final TimeUnit unit) {
    return tryAcquire(1, timeout, unit);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean tryAcquire(final int permits) {
    return tryAcquire(permits, 0, NANOSECONDS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean tryAcquire() {
    return tryAcquire(1, 0, NANOSECONDS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean tryAcquire(final int permits, final long timeout, final TimeUnit unit) {
    final long timeoutNano = max(unit.toNanos(timeout), 0);
    checkPermits(permits);
    long nanosToWait;
    synchronized (mutex) {
      final long elapsedNanos = System.nanoTime() - nanoStart;
      if (canAcquire(elapsedNanos, timeoutNano)) {
        nanosToWait = reserveAndGetWaitLength(permits, elapsedNanos);
      } else {
        return false;
      }
    }
    sleepNanosUninterruptibly(nanosToWait);
    return true;
  }

  private boolean canAcquire(final long elapsedNanos, final long timeoutNanos) {
    return nextFreeTicketNanos - timeoutNanos <= elapsedNanos;
  }

  /**
   * Reserves next ticket and returns the wait time that the caller must wait for.
   *
   * @return the required wait time, never negative
   */
  private long reserveAndGetWaitLength(final int permits, final long elapsedNanos) {
    final long momentAvailable = reserveEarliestAvailable(permits, elapsedNanos);
    return max(momentAvailable - elapsedNanos, 0);
  }

  /**
   * Reserves the requested number of permits and returns the time that those permits can be used
   * (with one caveat).
   *
   * @return the time that the permits may be used, or, if the permits may be used immediately, an
   * arbitrary past or present time
   */
  private long reserveEarliestAvailable(final int requiredPermits, final long elapsedNanos) {
    reSync(elapsedNanos);
    final long returnValue = nextFreeTicketNanos;
    final double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
    final double freshPermits = requiredPermits - storedPermitsToSpend;
    final long waitNanos = storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend)
        + (long) (freshPermits * stableIntervalNanos);
    this.nextFreeTicketNanos = saturatedAdd(nextFreeTicketNanos, waitNanos);
    this.storedPermits -= storedPermitsToSpend;
    return returnValue;
  }

  private static void sleepNanosUninterruptibly(long sleepNanos) {
    final long end = System.nanoTime() + sleepNanos;
    try {
      NANOSECONDS.sleep(sleepNanos);
      return;
    } catch (final InterruptedException e) {
      sleepNanos = end - System.nanoTime();
    }
    try {
      for (;;) {
        try {
          NANOSECONDS.sleep(sleepNanos);
          return;
        } catch (final InterruptedException e) {
          sleepNanos = end - System.nanoTime();
        }
      }
    } finally {
      Thread.currentThread().interrupt();
    }
  }

  private static void checkPermits(final int permits) {
    if (permits <= 0) {
      throw new IllegalArgumentException(String
          .format("Requested permits (%s) must be positive", permits));
    }
  }

  /**
   * Returns the sum of {@code a} and {@code b} unless it would overflow or underflow in which case
   * {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
   */
  private static long saturatedAdd(final long a, final long b) {
    final long naiveSum = a + b;
    if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
      return naiveSum;
    }
    return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
  }

  /**
   * Translates a specified portion of our currently stored permits which we want to spend/acquire,
   * into a throttling time. Conceptually, this evaluates the integral of the underlying function we
   * use, for the range of [(storedPermits - permitsToTake), storedPermits].
   *
   * <p>This always holds: {@code 0 <= permitsToTake <= storedPermits}
   */
  abstract long storedPermitsToWaitTime(final double storedPermits, final double permitsToTake);

  /**
   * Returns the number of nanoseconds during cool down that we have to wait to get a new permit.
   */
  abstract double coolDownIntervalNanos();

  /**
   * Updates {@code storedPermits} and {@code nextFreeTicketNanos} based on the current time.
   */
  private void reSync(final long elapsedNanos) {
    if (elapsedNanos > nextFreeTicketNanos) {
      final double newPermits = (elapsedNanos - nextFreeTicketNanos) / coolDownIntervalNanos();
      storedPermits = min(maxPermits, storedPermits + newPermits);
      nextFreeTicketNanos = elapsedNanos;
    }
  }

  /**
   * This implements the following function where coldInterval = coldFactor * stableInterval.
   *
   * ^ throttling
   * |
   * cold  +                  /
   * interval |                 /.
   * |                / .
   * |               /  .   <-- "warmup period" is the area of the trapezoid between
   * |              /   .       thresholdPermits and maxPermits
   * |             /    .
   * |            /     .
   * |           /      .
   * stable +----------/  WARM .
   * interval |          .   UP  .
   * |          . PERIOD.
   * |          .       .
   * 0 +----------+-------+--------------> storedPermits
   * 0 thresholdPermits maxPermits
   * Before going into the details of this particular function, let's keep in mind the basics:
   * 1) The state of the Throttle (storedPermits) is a vertical line in this figure.
   * 2) When the Throttle is not used, this goes right (up to maxPermits)
   * 3) When the Throttle is used, this goes left (down to zero), since if we have storedPermits,
   * we serve from those first
   * 4) When _unused_, we go right at a constant rate! The rate at which we move to
   * the right is chosen as maxPermits / warmupPeriod.  This ensures that the time it takes to
   * go from 0 to maxPermits is equal to warmupPeriod.
   * 5) When _used_, the time it takes, as explained in the introductory class note, is
   * equal to the integral of our function, between X permits and X-K permits, assuming
   * we want to spend K saved permits.
   *
   * In summary, the time it takes to move to the left (spend K permits), is equal to the
   * area of the function of width == K.
   *
   * Assuming we have saturated demand, the time to go from maxPermits to thresholdPermits is
   * equal to warmupPeriod.  And the time to go from thresholdPermits to 0 is
   * warmupPeriod/2.  (The reason that this is warmupPeriod/2 is to maintain the behavior of
   * the original implementation where coldFactor was hard coded as 3.)
   *
   * It remains to calculate thresholdsPermits and maxPermits.
   *
   * - The time to go from thresholdPermits to 0 is equal to the integral of the function between
   * 0 and thresholdPermits.  This is thresholdPermits * stableIntervals.  By (5) it is also
   * equal to warmupPeriod/2.  Therefore
   *
   * thresholdPermits = 0.5 * warmupPeriod / stableInterval.
   *
   * - The time to go from maxPermits to thresholdPermits is equal to the integral of the function
   * between thresholdPermits and maxPermits.  This is the area of the pictured trapezoid, and it
   * is equal to 0.5 * (stableInterval + coldInterval) * (maxPermits - thresholdPermits).  It is
   * also equal to warmupPeriod, so
   *
   * maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval).
   */
  static final class Warming extends NanoThrottle {

    private final long warmupPeriodNanos;
    private final double coldFactor;
    private volatile double slope;
    private volatile double thresholdPermits;

    Warming(final double permitsPerSecond, final long warmupPeriodNanos, final double coldFactor) {
      super(permitsPerSecond);
      this.warmupPeriodNanos = warmupPeriodNanos;
      this.coldFactor = coldFactor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void doSetRate(final double permitsPerSecond, final double stableIntervalNanos) {
      final double oldMaxPermits = maxPermits;
      final double coldIntervalNanos = stableIntervalNanos * coldFactor;
      thresholdPermits = warmupPeriodNanos / stableIntervalNanos / 2.0;
      maxPermits =
          thresholdPermits + 2.0 * warmupPeriodNanos / (stableIntervalNanos + coldIntervalNanos);
      slope = (coldIntervalNanos - stableIntervalNanos) / (maxPermits - thresholdPermits);
      if (oldMaxPermits == 0.0) {
        storedPermits = maxPermits;
        return;
      }
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        storedPermits = 0.0;
        return;
      }
      storedPermits = storedPermits * maxPermits / oldMaxPermits;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    long storedPermitsToWaitTime(final double storedPermits, double permitsToTake) {
      final double availablePermitsAboveThreshold = storedPermits - thresholdPermits;
      long nanos = 0;
      if (availablePermitsAboveThreshold > 0.0) {
        final double permitsAboveThresholdToTake =
            min(availablePermitsAboveThreshold, permitsToTake);
        final double length = permitsToTime(availablePermitsAboveThreshold)
            + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake);
        nanos = (long) (permitsAboveThresholdToTake * length / 2.0);
        permitsToTake -= permitsAboveThresholdToTake;
      }
      nanos += (stableIntervalNanos * permitsToTake);
      return nanos;
    }

    private double permitsToTime(final double permits) {
      return stableIntervalNanos + permits * slope;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    double coolDownIntervalNanos() {
      return warmupPeriodNanos / maxPermits;
    }
  }

  static final class Burst extends NanoThrottle {

    private final double maxBurstSeconds;

    Burst(final double permitsPerSecond, final double maxBurstSeconds) {
      super(permitsPerSecond);
      this.maxBurstSeconds = maxBurstSeconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalNanos) {
      final double oldMaxPermits = this.maxPermits;
      maxPermits = maxBurstSeconds * permitsPerSecond;
      if (oldMaxPermits == 0.0) {
        storedPermits = 0.0;
        return;
      }
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        storedPermits = maxPermits;
        return;
      }
      storedPermits = storedPermits * maxPermits / oldMaxPermits;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    long storedPermitsToWaitTime(final double storedPermits, final double permitsToTake) {
      return 0L;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    double coolDownIntervalNanos() {
      return stableIntervalNanos;
    }
  }

  @Override
  public String toString() {
    return String.format("NanoThrottle[stableRate=%3.1fqps]", getRate());
  }
}
