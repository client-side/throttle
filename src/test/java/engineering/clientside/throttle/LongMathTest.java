/*
 * Copyright (C) 2011 The Guava Authors
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

import static java.math.BigInteger.valueOf;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The following tests were adapted directly from com.google.common.math.LongMathTest
 *
 * @author Louis Wasserman - Original LongMathTest author
 * @author James P Edwards
 */
public class LongMathTest {

  private static final List<Long> ALL_LONG_CANDIDATES = new ArrayList<>();
  private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE);

  static {
    ALL_LONG_CANDIDATES.add((long) (Integer.MAX_VALUE - 1));
    ALL_LONG_CANDIDATES.add((long) Integer.MAX_VALUE);
    for (long i = 1; i <= 40; i++) {
      ALL_LONG_CANDIDATES.add(i);
    }
    ALL_LONG_CANDIDATES.add(Integer.MAX_VALUE + 1L);
    ALL_LONG_CANDIDATES.add(Long.MAX_VALUE - 1L);
    ALL_LONG_CANDIDATES.add(Long.MAX_VALUE);
    for (final int exponent :
        new int[]{2, 3, 4, 9, 15, 16, 17, 24, 25, 30, 32, 33, 39, 40, 41, 47, 48, 49, 55, 56, 57}) {
      final long x = 1L << exponent;
      ALL_LONG_CANDIDATES.add(x);
      ALL_LONG_CANDIDATES.add(x + 1);
      ALL_LONG_CANDIDATES.add(x - 1);
    }
    ALL_LONG_CANDIDATES.add(9999L);
    ALL_LONG_CANDIDATES.add(10000L);
    ALL_LONG_CANDIDATES.add(10001L);
    ALL_LONG_CANDIDATES.add(1000000L); // near powers of 10
    ALL_LONG_CANDIDATES.add(5792L);
    ALL_LONG_CANDIDATES.add(5793L);
    ALL_LONG_CANDIDATES.add(194368031998L);
    ALL_LONG_CANDIDATES.add(194368031999L);
    final List<Long> negativeLongCandidates = new ArrayList<>(ALL_LONG_CANDIDATES.size());
    for (final long l : ALL_LONG_CANDIDATES) {
      negativeLongCandidates.add(-l);
    }
    ALL_LONG_CANDIDATES.addAll(negativeLongCandidates);
    ALL_LONG_CANDIDATES.add(Long.MIN_VALUE);
    ALL_LONG_CANDIDATES.add(0L);
  }

  private static void assertOperationEquals(final long val1, final long val2, final long expected,
      final long actual) {
    if (expected != actual) {
      fail("Expected for " + val1 + " s+ " + val2 + " = " + expected
          + ", but got " + actual);
    }
  }

  private static long saturatedCast(BigInteger big) {
    if (big.compareTo(MAX_LONG) > 0) {
      return Long.MAX_VALUE;
    }
    if (big.compareTo(MIN_LONG) < 0) {
      return Long.MIN_VALUE;
    }
    return big.longValue();
  }

  @Test
  public void testSaturatedAdd() {
    for (final long a : ALL_LONG_CANDIDATES) {
      for (final long b : ALL_LONG_CANDIDATES) {
        assertOperationEquals(
            a, b,
            saturatedCast(valueOf(a).add(valueOf(b))),
            NanoThrottle.saturatedAdd(a, b));
      }
    }
  }
}
