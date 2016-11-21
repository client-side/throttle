/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package engineering.clientside.throttle;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static engineering.clientside.throttle.NanoThrottle.ONE_SECOND_NANOS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The following tests were adapted directly from com.google.common.util.concurrent.RateLimiterTest.
 * Changes were made to test the non-burst behavior of Throttle, to ensure the rate limit is not
 * exceeded over the period of one second.
 *
 * @author Dimitris Andreou - Original RateLimiterTest author
 * @author James P Edwards
 */
public class ThrottleTest {

  private static final double FIRST_DELTA = 0.007; // 7ms
  private static final double SECOND_DELTA = 0.006; // 6ms

  @BeforeClass
  public static void warmup() {
    Throttle.create(100.0);
  }

  @Test
  public void testReserve() throws InterruptedException {
    final NanoThrottle throttle = new NanoThrottle.GoldFish(5.0, 1.0, false);
    long sleep = throttle.reserve(1);
    NANOSECONDS.sleep(sleep);
    assertEquals(0.0, sleep / ONE_SECOND_NANOS, 0.0);
    sleep = throttle.reserve(1);
    NANOSECONDS.sleep(sleep);
    assertEquals(0.20, sleep / ONE_SECOND_NANOS, FIRST_DELTA);
    sleep = throttle.reserve(1);
    NANOSECONDS.sleep(sleep);
    assertEquals(0.20, sleep / ONE_SECOND_NANOS, SECOND_DELTA);
  }

  @Test
  public void testAcquire() throws InterruptedException {
    final Throttle throttle = Throttle.create(5.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.20, throttle.acquireUnchecked(), FIRST_DELTA);
    assertEquals(0.20, throttle.acquire(), SECOND_DELTA);
  }

  @Test
  public void testAcquireWeights() throws InterruptedException {
    final Throttle throttle = Throttle.create(20.0);
    assertEquals(0.00, throttle.acquireUnchecked(1), FIRST_DELTA);
    assertEquals(0.05, throttle.acquire(1), SECOND_DELTA);
    assertEquals(0.05, throttle.acquire(2), SECOND_DELTA);
    assertEquals(0.10, throttle.acquire(4), SECOND_DELTA);
    assertEquals(0.20, throttle.acquire(8), SECOND_DELTA);
    assertEquals(0.40, throttle.acquire(1), SECOND_DELTA);
  }

  @Test
  public void testAcquireWithWait() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    Thread.sleep(20);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.020, throttle.acquire(), SECOND_DELTA);
  }

  @Test
  public void testAcquireWithDoubleWait() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    Thread.sleep(40);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.020, throttle.acquire(), SECOND_DELTA);
    assertEquals(0.020, throttle.acquire(), SECOND_DELTA);
  }

  @Test
  public void testManyPermits() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.02, throttle.acquire(), FIRST_DELTA);
    assertEquals(0.02, throttle.acquire(3), SECOND_DELTA);
    assertEquals(0.06, throttle.acquire(), SECOND_DELTA);
    assertEquals(0.02, throttle.acquire(), SECOND_DELTA);
  }

  @Test
  public void testAcquireAndUpdate() throws InterruptedException {
    final Throttle throttle = Throttle.create(10.0);
    assertEquals(0.0, throttle.acquire(1), 0.0);
    assertEquals(0.10, throttle.acquire(1), FIRST_DELTA);

    throttle.setRate(20.0);

    assertEquals(0.10, throttle.acquire(1), SECOND_DELTA);
    assertEquals(0.05, throttle.acquire(2), SECOND_DELTA);
    assertEquals(0.10, throttle.acquire(4), SECOND_DELTA);
    assertEquals(0.20, throttle.acquire(1), SECOND_DELTA);
  }

  @Test
  public void testTryAcquire_noWaitAllowed() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertTrue(throttle.tryAcquire());
    assertFalse(throttle.tryAcquireUnchecked(0, SECONDS));
    assertFalse(throttle.tryAcquire(0, SECONDS));
    Thread.sleep(10);
    assertFalse(throttle.tryAcquire(0, SECONDS));
  }

  @Test
  public void testTryAcquire_someWaitAllowed() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertTrue(throttle.tryAcquire(0, SECONDS));
    assertTrue(throttle.tryAcquire(20, MILLISECONDS));
    assertFalse(throttle.tryAcquire(10, MILLISECONDS));
    Thread.sleep(10);
    assertTrue(throttle.tryAcquire(10, MILLISECONDS));
  }

  @Test
  public void testTryAcquire_overflow() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertTrue(throttle.tryAcquire(0, MICROSECONDS));
    Thread.sleep(10);
    assertTrue(throttle.tryAcquire(Long.MAX_VALUE, MICROSECONDS));
  }

  @Test
  public void testTryAcquire_negative() throws InterruptedException {
    final Throttle throttle = Throttle.create(50.0);
    assertTrue(throttle.tryAcquire(5, 0, SECONDS));
    Thread.sleep(90);
    assertFalse(throttle.tryAcquire(1, Long.MIN_VALUE, SECONDS));
    Thread.sleep(10);
    assertTrue(throttle.tryAcquire(1, -1, SECONDS));
  }

  @Test
  public void testImmediateTryAcquire() throws InterruptedException {
    final Throttle throttle = Throttle.create(1.0);
    assertTrue("Unable to acquire initial permit", throttle.tryAcquire());
    assertFalse("Capable of acquiring secondary permit", throttle.tryAcquire());
  }

  @Test
  public void testDoubleMinValueCanAcquireExactlyOnce() throws InterruptedException {
    final Throttle throttle = Throttle.create(Double.MIN_VALUE);
    assertTrue("Unable to acquire initial permit", throttle.tryAcquire());
    assertFalse("Capable of acquiring an additional permit", throttle.tryAcquire());
    Thread.sleep(10);
    assertFalse("Capable of acquiring an additional permit after sleeping", throttle.tryAcquire());
  }

  @Test
  public void testSimpleRateUpdate() {
    final Throttle throttle = Throttle.create(5.0);
    assertEquals(5.0, throttle.getRate(), 0.0);
    throttle.setRate(10.0);
    assertEquals(10.0, throttle.getRate(), 0.0);
    try {
      throttle.setRate(0.0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.setRate(-10.0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testAcquireParameterValidation() throws InterruptedException {
    final Throttle throttle = Throttle.create(999);
    try {
      throttle.acquire(0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.acquire(-1);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.tryAcquire(0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.tryAcquire(-1);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.tryAcquireUnchecked(0, 1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.tryAcquire(-1, 1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testIllegalConstructorArgs() throws InterruptedException {
    try {
      Throttle.create(Double.POSITIVE_INFINITY);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      Throttle.create(Double.NEGATIVE_INFINITY);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      Throttle.create(Double.NaN);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      Throttle.create(-.0000001);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      final Throttle throttle = Throttle.create(1.0);
      throttle.setRate(Double.POSITIVE_INFINITY);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      final Throttle throttle = Throttle.create(1.0);
      throttle.setRate(Double.NaN);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testInterruptUnchecked() throws InterruptedException {
    final Throttle throttle = Throttle.create(1);
    throttle.acquireUnchecked(10);

    final CompletableFuture<Throwable> futureEx = new CompletableFuture<>();
    Thread thread = new Thread(() -> {
      try {
        throttle.acquireUnchecked();
        futureEx.complete(null);
      } catch (CompletionException ex) {
        futureEx.complete(ex.getCause());
      }
    });
    thread.start();
    thread.interrupt();
    thread.join();
    assertFalse(throttle.tryAcquire());
    assertEquals(InterruptedException.class, futureEx.join().getClass());

    final CompletableFuture<Throwable> futureEx2 = new CompletableFuture<>();
    thread = new Thread(() -> {
      try {
        throttle.tryAcquireUnchecked(20, SECONDS);
        futureEx2.complete(null);
      } catch (CompletionException ex) {
        futureEx2.complete(ex.getCause());
      }
    });
    thread.start();
    thread.interrupt();
    thread.join();
    assertFalse(throttle.tryAcquire());
    assertEquals(InterruptedException.class, futureEx2.join().getClass());
  }

  @Test
  public void testMax() throws InterruptedException {
    final Throttle throttle = Throttle.create(Double.MAX_VALUE);

    assertEquals(0.0, throttle.acquire(Integer.MAX_VALUE / 4), 0.0);
    assertEquals(0.0, throttle.acquire(Integer.MAX_VALUE / 2), 0.0);
    assertEquals(0.0, throttle.acquireUnchecked(Integer.MAX_VALUE), 0.0);

    throttle.setRate(20.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.05, throttle.acquire(), SECOND_DELTA);

    throttle.setRate(Double.MAX_VALUE);
    assertEquals(0.05, throttle.acquire(), FIRST_DELTA);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
  }

  @Test
  public void testWeNeverGetABurstMoreThanOneSec() throws InterruptedException {
    final Throttle throttle = Throttle.create(100.0);
    final int[] rates = {10000, 100, 1000000, 1000, 100};
    for (final int oneSecWorthOfWork : rates) {
      throttle.setRate(oneSecWorthOfWork);
      final int oneHundredMillisWorthOfWork = (int) (oneSecWorthOfWork / 10.0);
      long durationMillis = measureTotalTimeMillis(throttle, oneHundredMillisWorthOfWork);
      assertEquals(100.0, durationMillis, 15.0);
      durationMillis = measureTotalTimeMillis(throttle, oneHundredMillisWorthOfWork);
      assertEquals(100.0, durationMillis, 15.0);
    }
  }

  private static long measureTotalTimeMillis(final Throttle throttle, int permits)
      throws InterruptedException {
    final Random random = ThreadLocalRandom.current();
    final long startTime = System.nanoTime();
    while (permits > 0) {
      final int nextPermitsToAcquire = Math.max(1, random.nextInt(permits));
      permits -= nextPermitsToAcquire;
      throttle.acquire(nextPermitsToAcquire);
    }
    throttle.acquire(1); // to repay for any pending debt
    return NANOSECONDS.toMillis(System.nanoTime() - startTime);
  }

  @Test
  public void testToString() {
    final Throttle throttle = Throttle.create(100.0);
    assertEquals("Throttle{rate=100.0}", throttle.toString());
  }

  @Test
  public void testStream() {
    final int qps = 1_024;
    final Throttle throttle = Throttle.create(qps);
    // warm-up
    IntStream stream = IntStream.range(0, 128).parallel();
    stream.forEach(index -> throttle.acquireUnchecked());

    stream = IntStream.range(0, qps).parallel();
    long start = System.nanoTime();
    stream.forEach(index -> throttle.acquireUnchecked());
    long duration = TimeUnit.MILLISECONDS
        .convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    assertTrue("Expected duration between 1,000 and 1,010ms. Observed " + duration,
        duration >= 1000 && duration < 1010);

    final Throttle fairThrottle = Throttle.create(qps, true);
    // warm-up
    IntStream.range(0, 128).parallel().forEach(index -> fairThrottle.acquireUnchecked());

    stream = IntStream.range(0, qps).parallel();
    start = System.nanoTime();
    stream.forEach(index -> fairThrottle.tryAcquireUnchecked(100_000_000, TimeUnit.NANOSECONDS));
    duration = TimeUnit.MILLISECONDS
        .convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    assertTrue("Expected duration between 1,000 and 1,050ms. Observed " + duration,
        duration >= 1000 && duration < 1050);
  }
}
