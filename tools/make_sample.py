#!/usr/bin/env python3
# 手工组装一个最小 wasm 模块：导出 add(i32,i32)->i32 与 fib(i32)->i32（递归）。
# 不依赖任何 wasm 工具链，仅用 LEB128 + section 拼装，便于自检。

def uleb(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7f
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)

def vec(items):
    data = b"".join(items)
    return uleb(len(items)) + data

def section(sid: int, payload: bytes) -> bytes:
    return bytes([sid]) + uleb(len(payload)) + payload

# 类型
I32 = 0x7f
functype_add = bytes([0x60]) + vec([bytes([I32]), bytes([I32])]) + vec([bytes([I32])])
functype_fib = bytes([0x60]) + vec([bytes([I32])]) + vec([bytes([I32])])
type_sec = section(1, vec([functype_add, functype_fib]))

# 函数
func_sec = section(3, vec([uleb(0), uleb(1)]))

# 导出
def export_entry(name: str, kind: int, idx: int):
    nb = name.encode()
    return uleb(len(nb)) + nb + bytes([kind]) + uleb(idx)
export_sec = section(7, vec([export_entry("add", 0x00, 0), export_entry("fib", 0x00, 1)]))

# 代码
LOCAL_GET = 0x20
I32_CONST = 0x41
I32_ADD = 0x6a
I32_SUB = 0x6b
I32_LT_S = 0x48
CALL = 0x10
IF = 0x04
ELSE = 0x05
END = 0x0b

add_body = (
    bytes([0x00])              # locals: 0
    + bytes([LOCAL_GET, 0x00])
    + bytes([LOCAL_GET, 0x01])
    + bytes([I32_ADD])
    + bytes([END])
)

fib_body = (
    bytes([0x00])              # locals: 0
    + bytes([LOCAL_GET, 0x00])
    + bytes([I32_CONST, 0x02])
    + bytes([I32_LT_S])
    + bytes([IF, I32])         # if (result i32)
    + bytes([LOCAL_GET, 0x00])
    + bytes([ELSE])
    + bytes([LOCAL_GET, 0x00])
    + bytes([I32_CONST, 0x01])
    + bytes([I32_SUB])
    + bytes([CALL, 0x01])      # call fib
    + bytes([LOCAL_GET, 0x00])
    + bytes([I32_CONST, 0x02])
    + bytes([I32_SUB])
    + bytes([CALL, 0x01])      # call fib
    + bytes([I32_ADD])
    + bytes([END])             # end if
    + bytes([END])             # end func
)

def code(body: bytes) -> bytes:
    return uleb(len(body)) + body

code_sec = section(10, vec([code(add_body), code(fib_body)]))

wasm = b"\x00asm\x01\x00\x00\x00" + type_sec + func_sec + export_sec + code_sec

import sys
out = sys.argv[1] if len(sys.argv) > 1 else "sample.wasm"
with open(out, "wb") as f:
    f.write(wasm)
print(f"wrote {out}: {len(wasm)} bytes")
print("hex:", wasm.hex())
