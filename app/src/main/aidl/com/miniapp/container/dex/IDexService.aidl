package com.miniapp.container.dex;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * 隔离进程 Dex 执行接口。
 * params 中需包含 className / methodName（余下键值作为字符串参数传入）。
 * dexFd 为 dex 文件；inputFd / outputFd 可选，为数据文件（隔离进程仅能通过 FD 读写）。
 */
interface IDexService {
    Bundle run(in Bundle params, in ParcelFileDescriptor dexFd, in ParcelFileDescriptor inputFd, in ParcelFileDescriptor outputFd);
}
