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
 * Uses 8 bits per slot in a byte array.
 * @author Lee Rhodes
 * @author Kevin Lang
 */
class DirectHll8Array extends DirectHllArray {

  DirectHll8Array(final int lgConfigK, final WritableMemory wmem) {
    super(lgConfigK, TgtHllType.HLL_8, wmem);
  }

  DirectHll8Array(final int lgConfigK, final Memory mem) {
    super(lgConfigK, TgtHllType.HLL_8, mem);
  }

  @Override
  HllSketchImpl copy() {
    return Hll8Array.heapify(mem);
  }

  @Override
  HllSketchImpl couponUpdate(final int coupon) {
    final int configKmask = (1 << getLgConfigK()) - 1;
    final int slotNo = HllUtil.getLow26(coupon) & configKmask;
    final int newVal = HllUtil.getValue(coupon);
    assert newVal > 0;

    final long byteOffset = HLL_BYTE_ARR_START + slotNo;
    final int curVal = unsafe.getByte(memObj, memAdd + byteOffset);
    if (newVal > curVal) {
      unsafe.putByte(memObj, memAdd + byteOffset, (byte) (newVal & VAL_MASK_6));
      hipAndKxQIncrementalUpdate(this, curVal, newVal);
      if (curVal == 0) {
        decNumAtCurMin(); //overloaded as num zeros
        assert getNumAtCurMin() >= 0;
      }
    }
    return this;
  }

  @Override
  int getHllByteArrBytes() {
    return hll8ArrBytes(lgConfigK);
  }

  @Override
  int getSlot(final int slotNo) {
    return unsafe.getByte(memObj, memAdd + HLL_BYTE_ARR_START + slotNo);
  }

  @Override
  void putSlot(final int slotNo, final int newValue) {
    unsafe.putByte(memObj,  memAdd + HLL_BYTE_ARR_START + slotNo, (byte) newValue);
  }

  //ITERATOR
  @Override
  PairIterator getIterator() {
    return new DirectHll8Iterator(1 << lgConfigK);
  }

  final class DirectHll8Iterator extends HllPairIterator {

    DirectHll8Iterator(final int lengthPairs) {
      super(lengthPairs);
    }

    @Override
    int value() {
      final int tmp = unsafe.getByte(memObj, memAdd + HLL_BYTE_ARR_START + index);
      return tmp & VAL_MASK_6;
    }
  }

}
