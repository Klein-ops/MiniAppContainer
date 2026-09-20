package com.miniapp.container;

import android.os.Bundle;

/**
 * 存储 UserService 接口：以 shell（ADB）身份运行在独立进程，
 * 直接用 File API 操作内部储存。宿主通过 Shizuku.bindUserService 绑定。
 */
interface IStorageUserService {

    /** Shizuku 保留的销毁方法（事务码 16777114 / aidl 中 16777114 同值）。 */
    void destroy() = 16777114;

    byte[] read(String path);

    void write(String path, in byte[] data);

    /** 返回 Bundle[]：每个元素含 name / isDir / size。 */
    android.os.Bundle[] list(String dir);

    boolean exists(String path);

    /** Bundle：exists / isDir / size / name / canRead / canWrite / lastModified。 */
    android.os.Bundle stat(String path);

    boolean mkdir(String dir);

    boolean remove(String path);

    boolean rename(String from, String to);
}
