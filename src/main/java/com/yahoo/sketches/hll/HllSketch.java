/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.sketches.hll.HllUtil.checkPreamble;
import static com.yahoo.sketches.hll.PreambleUtil.HLL_BYTE_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgK;
import static com.yahoo.sketches.hll.PreambleUtil.extractTgtHllType;

import com.yahoo.memory.Memory;
import com.yahoo.memory.WritableMemory;

/**
 * This is a high performance implementation of Phillipe Flajolet&#8217;s HLL sketch but with
 * significantly improved error behavior.  If the ONLY use case for sketching is counting
 * uniques and merging, the HLL sketch is the highest performing in terms of accuracy for
 * storage space consumed. For large enough counts, this HLL version (with HLL_4) can be 2 to
 * 16 times smaller than the Theta sketch family for the same accuracy.
 *
 * <p>This implementation offers three different types of HLL sketch, each with different
 * trade-offs with accuracy, space and performance. These types are specified with the
 * {@link TgtHllType} parameter.
 *
 * <p>In terms of accuracy, all three types, for the same <i>lgConfigK</i>, have the same error
 * distribution as a function of <i>n</i>, the number of unique values fed to the sketch.
 * The configuration parameter <i>lgConfigK</i> is the log-base-2 of <i>K</i>,
 * where <i>K</i> is the number of buckets or slots for the sketch.
 *
 * <p>During warmup, when the sketch has only received a small number of unique items
 * (up to about 10% of <i>K</i>), this implementation leverages a new class of estimator
 * algorithms with significantly better accuracy.
 *
 * @author Lee Rhodes
 * @author Kevin Lang
 */
public class HllSketch extends BaseHllSketch {
  private static final String LS = System.getProperty("line.separator");
  HllSketchImpl hllSketchImpl = null;

  /**
   * Constructs a new sketch with a HLL_4 sketch as the default.
   * @param lgConfigK The Log2 of K for the target HLL sketch. This value must be
   * between 4 and 21 inclusively.
   */
  public HllSketch(final int lgConfigK) {
    this(lgConfigK, TgtHllType.HLL_4);
  }

  /**
   * Constructs a new sketch with the type of HLL sketch to configure.
   * @param lgConfigK The Log2 of K for the target HLL sketch. This value must be
   * between 4 and 21 inclusively.
   * @param tgtHllType the desired Hll type.
   */
  public HllSketch(final int lgConfigK, final TgtHllType tgtHllType) {
    hllSketchImpl = new CouponList(HllUtil.checkLgK(lgConfigK), tgtHllType, CurMode.LIST);
  }

  /**
   * Constructs a new direct sketch with the type of HLL sketch to configure and the given
   * WritableMemory as the destination for the sketch.
   * This checks that the given <i>dstMem</i> has the required capacity as determined by
   * {@link #getMaxUpdatableSerializationBytes(int, TgtHllType)}.
   * @param lgConfigK The Log2 of K for the target HLL sketch. This value must be
   * between 4 and 21 inclusively.
   * @param tgtHllType the desired Hll type.
   * @param dstMem the destination memory for the sketch.
   */
  public HllSketch(final int lgConfigK, final TgtHllType tgtHllType, final WritableMemory dstMem) {
    final long minBytes = getMaxUpdatableSerializationBytes(lgConfigK, tgtHllType);
    final long capBytes = dstMem.getCapacity();
    HllUtil.checkMemSize(minBytes, capBytes);
    hllSketchImpl = DirectCouponList.newInstance(lgConfigK, tgtHllType, dstMem);
  }

  /**
   * Copy constructor used by copy().
   * @param that another HllSketch
   */
  HllSketch(final HllSketch that) {
    hllSketchImpl = that.hllSketchImpl.copy();
  }

  /**
   * Special constructor used by copyAs, heapify
   * @param that another HllSketchImpl, which must already be a copy
   */
  HllSketch(final HllSketchImpl that) {
    hllSketchImpl = that;
  }

  /**
   * Heapify the given byte array as read-only.
   * The byte-array must be a valid HllSketch image and may have data.
   * @param byteArray the given byte array
   * @return an HllSketch
   */
  public static final HllSketch heapify(final byte[] byteArray) {
    return heapify(Memory.wrap(byteArray));
  }

  /**
   * Heapify the given read-only Memory, which must be a valid HllSketch image and may have data.
   * @param srcMem the given Memory
   * @return an HllSketch
   */
  public static final HllSketch heapify(final Memory srcMem) {
    final Object memObj = ((WritableMemory) srcMem).getArray();
    final long memAdd = srcMem.getCumulativeOffset(0);
    final CurMode curMode = checkPreamble(srcMem);
    final HllSketch heapSketch;
    if (curMode == CurMode.HLL) {
      final TgtHllType tgtHllType = extractTgtHllType(memObj, memAdd);
      if (tgtHllType == TgtHllType.HLL_4) {
        heapSketch = new HllSketch(Hll4Array.heapify(srcMem));
      } else if (tgtHllType == TgtHllType.HLL_6) {
        heapSketch = new HllSketch(Hll6Array.heapify(srcMem));
      } else { //Hll_8
        heapSketch = new HllSketch(Hll8Array.heapify(srcMem));
      }
    } else if (curMode == CurMode.LIST) {
      heapSketch = new HllSketch(CouponList.heapifyList(srcMem));
    } else {
      heapSketch = new HllSketch(CouponHashSet.heapifySet(srcMem));
    }
    return heapSketch;
  }

  /**
   * Wraps the given WritableMemory that will be initialized with a new instance of an HllSketch.
   * @param lgConfigK The Log2 of K for the target HLL sketch. This value must be
   * between 4 and 21 inclusively.
   * @param tgtHllType the desired Hll type.
   * @param wmem the given WritableMemory of sufficient size. Refer to
   * {@link #getMaxUpdatableSerializationBytes(int, TgtHllType)}.
   * @return a wrapped new instance of an HllSketch.
   */
  public static final HllSketch writableWrap(final int lgConfigK, final TgtHllType tgtHllType,
      final WritableMemory wmem) {
    final long minBytes = getMaxUpdatableSerializationBytes(lgConfigK, tgtHllType);
    final long capBytes = wmem.getCapacity();
    HllUtil.checkMemSize(minBytes, capBytes);
    wmem.clear(0L, minBytes);
    final HllSketch directSketch =
        new HllSketch(DirectCouponList.newInstance(lgConfigK, tgtHllType, wmem));
    return directSketch;
  }

  /**
   * Wraps the given WritableMemory that must be a image of a valid sketch, and may have data.
   * @param wmem an image of a valid sketch with data that will also be written to.
   * @return an HllSketch
   */
  public static final HllSketch writableWrap(final WritableMemory wmem) {
    final Object memObj = wmem.getArray();
    final long memAdd = wmem.getCumulativeOffset(0);
    final int lgConfigK = extractLgK(memObj, memAdd);
    final TgtHllType tgtHllType = extractTgtHllType(memObj, memAdd);
    final long minBytes = getMaxUpdatableSerializationBytes(lgConfigK, tgtHllType);
    final long capBytes = wmem.getCapacity();
    HllUtil.checkMemSize(minBytes, capBytes);
    HllUtil.checkPreamble(wmem);

    final CurMode curMode = checkPreamble(wmem);
    final HllSketch directSketch;
    if (curMode == CurMode.HLL) {
      if (tgtHllType == TgtHllType.HLL_4) {
        directSketch = new HllSketch(new DirectHll4Array(lgConfigK, wmem));
      } else if (tgtHllType == TgtHllType.HLL_6) {
        directSketch = new HllSketch(new DirectHll6Array(lgConfigK, wmem));
      } else { //Hll_8
        directSketch = new HllSketch(new DirectHll8Array(lgConfigK, wmem));
      }
    } else if (curMode == CurMode.LIST) {
      directSketch =
          new HllSketch(new DirectCouponList(lgConfigK, tgtHllType, curMode, wmem));
    } else {
      directSketch =
          new HllSketch(new DirectCouponHashSet(lgConfigK, tgtHllType, wmem));
    }
    return directSketch;
  }

  /**
   * Wraps the given read-only Memory that must be a image of a valid sketch, and may have data.
   * @param srcMem an image of a valid sketch with data.
   * @return an HllSketch
   */
  public static final HllSketch wrap(final Memory srcMem) {
    final Object memObj = ((WritableMemory) srcMem).getArray();
    final long memAdd = srcMem.getCumulativeOffset(0);
    final int lgConfigK = extractLgK(memObj, memAdd);
    final TgtHllType tgtHllType = extractTgtHllType(memObj, memAdd);
    HllUtil.checkPreamble(srcMem);

    final CurMode curMode = checkPreamble(srcMem);
    final HllSketch directSketch;
    if (curMode == CurMode.HLL) {
      if (tgtHllType == TgtHllType.HLL_4) {
        directSketch = new HllSketch(new DirectHll4Array(lgConfigK, srcMem));
      } else if (tgtHllType == TgtHllType.HLL_6) {
        directSketch = new HllSketch(new DirectHll6Array(lgConfigK, srcMem));
      } else { //Hll_8
        directSketch = new HllSketch(new DirectHll8Array(lgConfigK, srcMem));
      }
    } else if (curMode == CurMode.LIST) {
      directSketch =
          new HllSketch(new DirectCouponList(lgConfigK, tgtHllType, curMode, srcMem));
    } else { //SET
      directSketch =
          new HllSketch(new DirectCouponHashSet(lgConfigK, tgtHllType, srcMem));
    }
    return directSketch;
  }

  /**
   * Return a copy of this sketch onto the Java heap.
   * @return a copy of this sketch onto the Java heap.
   */
  public HllSketch copy() {
    return new HllSketch(this);
  }

  /**
   * Return a deep copy of this sketch onto the Java heap with the specified TgtHllType.
   * @param tgtHllType the TgtHllType enum
   * @return a deep copy of this sketch with the specified TgtHllType.
   */
  public HllSketch copyAs(final TgtHllType tgtHllType) {
    return new HllSketch(hllSketchImpl.copyAs(tgtHllType));
  }

  @Override
  public double getCompositeEstimate() {
    return hllSketchImpl.getCompositeEstimate();
  }

  @Override
  CurMode getCurMode() {
    return hllSketchImpl.getCurMode();
  }

  @Override
  public double getEstimate() {
    return hllSketchImpl.getEstimate();
  }

  @Override
  public int getLgConfigK() {
    return hllSketchImpl.getLgConfigK();
  }

  @Override
  public int getCompactSerializationBytes() {
    return hllSketchImpl.getCompactSerializationBytes();
  }

  @Override
  public double getLowerBound(final int numStdDev) {
    return hllSketchImpl.getLowerBound(numStdDev);
  }

  /**
   * Returns the maximum size in bytes that this sketch can grow to given lgConfigK.
   * However, for the HLL_4 sketch type, this value can be exceeded in extremely rare cases.
   * If exceeded, it will be larger by only a few percent.
   *
   * @param lgConfigK The Log2 of K for the target HLL sketch. This value must be
   * between 4 and 21 inclusively.
   * @param tgtHllType the desired Hll type
   * @return the maximum size in bytes that this sketch can grow to.
   */
  public static final int getMaxUpdatableSerializationBytes(final int lgConfigK,
      final TgtHllType tgtHllType) {
    final int arrBytes;
    if (tgtHllType == TgtHllType.HLL_4) {
      final int auxBytes = 4 << AbstractHllArray.getExpectedLgAuxInts(lgConfigK);
      arrBytes =  AbstractHllArray.hll4ArrBytes(lgConfigK) + auxBytes;
    }
    else if (tgtHllType == TgtHllType.HLL_6) {
      arrBytes = AbstractHllArray.hll6ArrBytes(lgConfigK);
    }
    else { //HLL_8
      arrBytes = AbstractHllArray.hll8ArrBytes(lgConfigK);
    }
    return HLL_BYTE_ARR_START + arrBytes;
  }

  @Override
  public double getRelErr(final int numStdDev) {
    return hllSketchImpl.getRelErr(numStdDev);
  }

  @Override
  public double getRelErrFactor(final int numStdDev) {
    return hllSketchImpl.getRelErrFactor(numStdDev);
  }

  @Override
  public int getUpdatableSerializationBytes() {
    return hllSketchImpl.getUpdatableSerializationBytes();
  }

  @Override
  public double getUpperBound(final int numStdDev) {
    return hllSketchImpl.getUpperBound(numStdDev);
  }

  /**
   * Gets the {@link TgtHllType}
   * @return the TgtHllType enum value
   */
  public TgtHllType getTgtHllType() {
    return hllSketchImpl.getTgtHllType();
  }

  @Override
  public boolean isEmpty() {
    return hllSketchImpl.isEmpty();
  }

  @Override
  public boolean isMemory() {
    return hllSketchImpl.isMemory();
  }

  @Override
  public boolean isOffHeap() {
    return hllSketchImpl.isOffHeap();
  }

  @Override
  boolean isOutOfOrderFlag() {
    return hllSketchImpl.isOutOfOrderFlag();
  }

  /**
   * Resets to empty, but does not change the configured values of lgConfigK and tgtHllType.
   */
  @Override
  public void reset() {
    hllSketchImpl = new CouponList(hllSketchImpl.getLgConfigK(), hllSketchImpl.getTgtHllType(),
        CurMode.LIST);
  }

  /**
   * Gets the serialization of this sketch as a byte array in compact form, which is designed
   * to be heapified only. It is not directly updatable.
   * @return the serialization of this sketch as a byte array.
   */
  @Override
  public byte[] toCompactByteArray() {
    return hllSketchImpl.toCompactByteArray();
  }

  @Override
  public byte[] toUpdatableByteArray() {
    return hllSketchImpl.toUpdatableByteArray();
  }

  @Override
  public String toString() {
    return toString(true, false, false, false);
  }

  @Override
  public String toString(final boolean summary, final boolean detail, final boolean auxDetail,
      final boolean all) {
    final StringBuilder sb = new StringBuilder();
    if (summary) {
      sb.append("### HLL SKETCH SUMMARY: ").append(LS);
      sb.append("  Log Config K   : ").append(getLgConfigK()).append(LS);
      sb.append("  Hll Target     : ").append(getTgtHllType()).append(LS);
      sb.append("  Current Mode   : ").append(getCurrentMode()).append(LS);
      sb.append("  LB             : ").append(getLowerBound(1)).append(LS);
      sb.append("  Estimate       : ").append(getEstimate()).append(LS);
      sb.append("  UB             : ").append(getUpperBound(1)).append(LS);
      sb.append("  OutOfOrder Flag: ").append(isOutOfOrderFlag()).append(LS);
      if (getCurrentMode() == CurMode.HLL) {
        final AbstractHllArray absHll = (AbstractHllArray) hllSketchImpl;
        sb.append("  CurMin         : ").append(absHll.getCurMin()).append(LS);
        sb.append("  NumAtCurMin    : ").append(absHll.getNumAtCurMin()).append(LS);
        sb.append("  HipAccum       : ").append(absHll.getHipAccum()).append(LS);
      } else {
        sb.append("  Coupon Count   : ")
          .append(((AbstractCoupons)hllSketchImpl).getCouponCount()).append(LS);
      }
    }
    if (detail) {
      sb.append("### HLL SKETCH DATA DETAIL: ").append(LS);
      final PairIterator pitr = getIterator();
      sb.append(pitr.getHeader()).append(LS);
      if (all) {
        while (pitr.nextAll()) {
          sb.append(pitr.getString()).append(LS);
        }
      } else {
        while (pitr.nextValid()) {
          sb.append(pitr.getString()).append(LS);
        }
      }
    }
    if (auxDetail) {
      if ((getCurrentMode() == CurMode.HLL) && (getTgtHllType() == TgtHllType.HLL_4)) {
        final AbstractHllArray absHll = (AbstractHllArray) hllSketchImpl;
        final PairIterator auxItr = absHll.getAuxIterator();
        if (auxItr != null) {
          sb.append("### HLL SKETCH AUX DETAIL: ").append(LS);
          sb.append(auxItr.getHeader()).append(LS);
          if (all) {
            while (auxItr.nextAll()) {
              sb.append(auxItr.getString()).append(LS);
            }
          } else {
            while (auxItr.nextValid()) {
              sb.append(auxItr.getString()).append(LS);
            }
          }
        }
      }
    }
    return sb.toString();
  }

  //restricted methods

  /**
   * Gets a PairIterator over the key, value pairs of the HLL array.
   * @return a PairIterator over the key, value pairs of the HLL array.
   */
  PairIterator getIterator() {
    return hllSketchImpl.getIterator();
  }

  CurMode getCurrentMode() {
    return hllSketchImpl.getCurMode();
  }

  @Override
  void couponUpdate(final int coupon) {
    hllSketchImpl = hllSketchImpl.couponUpdate(coupon);
  }

}
