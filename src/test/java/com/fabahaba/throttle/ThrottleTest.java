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

package com.fabahaba.throttle;

import org.junit.Test;

import static com.fabahaba.throttle.NanoThrottle.ONE_SECOND_NANOS;
import static com.fabahaba.throttle.NanoThrottle.sleepNanosUninterruptibly;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The following tests were adapted directly from com.google.common.util.concurrent.RateLimiterTest
 *
 * @author Dimitris Andreou - Original RateLimiterTest author
 * @author James P Edwards
 */
public class ThrottleTest {

  private static final double FIRST_DELTA = 0.007; // 7ms
  private static final double SECOND_DELTA = 0.006; // 6ms

  @Test
  public void testReserve() {
    final NanoThrottle throttle = new NanoThrottle.GoldFish(5.0, 1.0);
    long sleep = throttle.reserve(1);
    sleepNanosUninterruptibly(sleep);
    assertEquals(0.0, sleep / ONE_SECOND_NANOS, 0.0);
    sleep = throttle.reserve(1);
    sleepNanosUninterruptibly(sleep);
    assertEquals(0.20, sleep / ONE_SECOND_NANOS, FIRST_DELTA);
    sleep = throttle.reserve(1);
    sleepNanosUninterruptibly(sleep);
    assertEquals(0.20, sleep / ONE_SECOND_NANOS, SECOND_DELTA);
  }

  @Test
  public void testAcquire() {
    final Throttle throttle = Throttle.create(5.0);
    assertEquals(0.0, throttle.acquire(), 0.0);
    assertEquals(0.20, throttle.acquire(), FIRST_DELTA);
    assertEquals(0.20, throttle.acquire(), SECOND_DELTA);
  }

  @Test
  public void testAcquireWeights() {
    final Throttle throttle = Throttle.create(10.0);
    assertEquals(0.00, throttle.acquire(1), FIRST_DELTA);
    assertEquals(0.10, throttle.acquire(1), SECOND_DELTA);
    assertEquals(0.10, throttle.acquire(2), SECOND_DELTA);
    assertEquals(0.20, throttle.acquire(4), SECOND_DELTA);
    assertEquals(0.40, throttle.acquire(8), SECOND_DELTA);
    assertEquals(0.80, throttle.acquire(1), SECOND_DELTA);
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
  public void testAcquireAndUpdate() {
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
    assertTrue(throttle.tryAcquire(0, SECONDS));
    assertFalse(throttle.tryAcquire(0, SECONDS));
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
  public void testImmediateTryAcquire() {
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
  public void testAcquireParameterValidation() {
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
      throttle.tryAcquire(0, 1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      throttle.tryAcquire(-1, 1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
