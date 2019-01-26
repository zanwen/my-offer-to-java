package top.zhenganwen.jvmtest.exectmpcode;

import java.util.Arrays;

public class ByteUtils {

    public static boolean isLegalArrayIndex(byte[] bytes, int index) {
        if (bytes == null || bytes.length == 0 || index < 0 || index > bytes.length-1) {
            return false;
        }
        return true;
    }

    public static int bytes2Int(byte[] bytes, int start, int len) {
        if (!isLegalArrayIndex(bytes, start) ||
                !isLegalArrayIndex(bytes, start + len - 1) || len <= 0) {

            throw new IllegalArgumentException();
        }
        int end = start + len;
        int sum = 0;
        for (int i = start; i < end; i++) {
            sum += ((int) bytes[i]) << (--len * 8);
        }
        return sum;
    }

    public static String bytes2String(byte[] bytes, int start, int len) {
        if (!isLegalArrayIndex(bytes, start) ||
                !isLegalArrayIndex(bytes, start + len - 1) || len <= 0) {

            throw new IllegalArgumentException();
        }
        return new String(bytes, start, len);
    }

    public static byte[] int2Bytes(int value, int len) {
        if (value < 0 || len <= 0) {
            throw new IllegalArgumentException();
        }
        byte[] res = new byte[len];
        for (int i = 0; i < len; i++) {
            res[len - i - 1] = (byte) ((value >> (8 * i)) & 0xff);
        }
        return res;
    }

    public static byte[] replace(byte[] classByte, int start, int len, byte[] bytes) {
        if (!isLegalArrayIndex(classByte, start) ||
                !isLegalArrayIndex(classByte, start + len - 1) || len <= 0) {

            throw new IllegalArgumentException();
        }
        byte[] res = new byte[classByte.length + (bytes.length - len)];
        System.arraycopy(classByte, 0, res, 0, start);
        System.arraycopy(bytes, 0, res, start, bytes.length);
        System.arraycopy(classByte, start + len, res, start + bytes.length, classByte.length - start - len);
        return res;
    }
}
