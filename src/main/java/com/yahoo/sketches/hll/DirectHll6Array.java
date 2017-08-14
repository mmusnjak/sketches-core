/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.memory.UnsafeUtil.unsafe;
import static com.yahoo.sketches.hll.HllUtil.VAL_MASK_6;
import static com.yahoo.sketches.hll.PreambleUtil.HLL_BYTE_ARR_START;

import com.yahoo.memory.Memory;
import com.yahoo.memory.WritableMemory;

/**
 * @author Lee Rhodes
 */
class DirectHll6Array extends DirectHllArray {

  DirectHll6Array(final int lgConfigK, final WritableMemory wmem) {
    super(lgConfigK, TgtHllType.HLL_6, wmem);
  }

  DirectHll6Array(final int lgConfigK, final Memory mem) {
    super(lgConfigK, TgtHllType.HLL_6, mem);
  }

  @Override
  HllSketchImpl copy() {
    return Hll6Array.heapify(mem);
  }

  @Override
  HllSketchImpl couponUpdate(final int coupon) {
    final int configKmask = (1 << getLgConfigK()) - 1;
    final int slotNo = HllUtil.getLow26(coupon) & configKmask;
    final int newVal = HllUtil.getValue(coupon);
    assert newVal > 0;
    final int curVal = Hll6Array.get6Bit(mem, HLL_BYTE_ARR_START, slotNo);
    if (newVal > curVal) {
      Hll6Array.put6Bit(wmem, HLL_BYTE_ARR_START, slotNo, newVal);
      hipAndKxQIncrementalUpdate(curVal, newVal);
      if (curVal == 0) {
        decNumAtCurMin(); //overloaded as num zeros
        assert getNumAtCurMin() >= 0;
      }
    }
    return this;
  }

  @Override
  int getHllByteArrBytes() {
    return hll6ArrBytes(lgConfigK);
  }

  //ITERATOR
  @Override
  PairIterator getIterator() {
    return new DirectHll6Iterator(mem, HLL_BYTE_ARR_START, 1 << lgConfigK);
  }

  final class DirectHll6Iterator extends HllMemoryPairIterator {
    int bitOffset;

    DirectHll6Iterator(final Memory mem, final long offsetBytes, final int lengthPairs) {
      super(mem, offsetBytes, lengthPairs);
      bitOffset = -6;
    }

    @Override
    int value() {
      bitOffset += 6;
      final int tmp = unsafe.getShort(memObj, memAdd + offsetBytes + (bitOffset / 8));
      final int shift = (bitOffset % 8) & 0X7;
      return (tmp >>> shift) & VAL_MASK_6;
    }
  }

}
