package top.zhenganwen.jvmtest.exectmpcode;

import java.io.UnsupportedEncodingException;

/**
 * ClassModifier class
 * <p>
 * 更改字节码文件的常量池部分，支持JDK4-JDK7
 * <p>
 * 暂时只支持将常量池部分中的Constant_UTF8_info常量值
 * 更改方式是将按照提供的新值、旧值，用新值将旧值替换掉
 * <p>
 * 通过此类实现替换java.lang.System的符号引用为我们自定义的
 * top.zhenganwen.HackSystem，以实现将待执行的临时代码
 * 的输出内容写入字节流并返回给客户端
 *
 * @author zhenganwen, blog:zhenganwen.top
 * @date 2019/1/25
 */
public class ClassModifier {

    /**
     * 常量池在Class文件中的起始偏移量
     * magic(4B) + major version(2B) + minor version(2B) = 8B
     * 略去前8个字节，第九个字节的在字节流中的索引是8
     */
    public static final int CONSTANT_POOL_INDEX = 8;

    /**
     * Constant_UTF8_info常量的tag标志为1
     */
    public static final int CONSTANT_UTF8_INFO_TAG = 1;


    /**
     * 每项常量所占长度（不包括JDK7新增的关于动态调用的三项常量）
     * tag为1时是Constant_UTF8_info，长度不固定，因此用-1
     * 由于tag为0和2没有关联的常量，因此索引0和2也用-1表示
     * tag为3时是Constant_Integer_info，共占5个字节
     * tag为3时是Constant_Float_info，共占5个字节
     * 以此类推
     */
    public static final int[] CONSTANT_ITEM_LENGTH = new int[]{-1, -1, -1, 5, 5, 9, 9, 3, 3, 5, 5, 5, 5};

    /**
     * u1表示无符号1个字节，u2则表示无符号两个字节
     */
    public static final int u1 = 1;
    public static final int u2 = 2;

    /**
     * 要更改的二进制字节流
     */
    private byte[] classByte;

    public ClassModifier(byte[] classByte) {
        this.classByte = classByte;
    }

    public static final String UTF8_ENCODING = "UTF-8";

    /**
     * 替换符号引用以实现待执行程序中的System.out使用的是我们自定义的
     * HackSystem而不是java.lang.System（会将System.out的输出行为
     * 定向到到服务端，而我们需要将System.out的调用委派给我们的
     * HackSystem将输出返回给客户端）
     *
     * @param oldStr
     * @param newStr
     * @return 返回更改后的二进制字节流
     */
    public byte[] modifyUTF8Constant(String oldStr, String newStr) throws UnsupportedEncodingException {

        int cpc = getConstantPoolCount();
        //constant_pool_count -> u2
        int index = CONSTANT_POOL_INDEX + u2;
        //依次扫描cpc项常量，遇到字面量为oldStr的常量就用newStr替换他并返回
        for (int i = 0; i < cpc; i++) {
            int tag = ByteUtils.bytes2Int(classByte, index, u1);
            //如果是UTF8字符串则查看字面量是否是oldStr
            if (tag == CONSTANT_UTF8_INFO_TAG) {
                //字符串长度最长为65535B，也是字段、方法名（转换成UTF8存储）的最大长度
                int length = ByteUtils.bytes2Int(classByte, index + 1, u2);
                // tag -> u1  length -> u2
                index += (u1 + u2);
                if (length > 0) {
                    String str = ByteUtils.bytes2String(classByte, index, length);
                    if (str != null && str.length() != 0 && str.equalsIgnoreCase(oldStr)) {
                        byte[] newBytes = newStr.getBytes(UTF8_ENCODING);
                        //用新串字节码替换旧串字节码
                        ByteUtils.replace(classByte, index, length, newBytes);
                        //别忘了将标识该串的length也要改为一致
                        byte[] len = ByteUtils.int2Bytes(newBytes.length, u2);
                        ByteUtils.replace(classByte, index - u2, u2, len);
                        //更改成功就可返回了，只需更改这一处
                        return classByte;
                    } else {
                        index += length;
                    }
                }
            }
            //如果是其它常量，根据常量tag类型跳过相应字节数
            else {
                index += CONSTANT_ITEM_LENGTH[tag];
            }
        }

        return classByte;
    }

    /**
     * @return 返回常量池的大小，constant_pool是一个u2类型
     */
    public int getConstantPoolCount() {
        return ByteUtils.bytes2Int(classByte, CONSTANT_POOL_INDEX, u2);
    }
}
