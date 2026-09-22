"""局域网 → 桌面版回环端口的转发器（App 联网的前置依赖）。

为什么需要它
------------
opencode 桌面版只绑 127.0.0.1:49374，手机连不上。
改系统路由（netsh portproxy）要管理员权限、而且是持久性的系统改动；
这里用用户态转发：不开管理员、不动系统配置，Ctrl+C 或杀进程就干净了。

它只做字节搬运，不解析也不改内容 —— 所有鉴权仍然是 opencode 自己的 Basic Auth。

用法
----
    python tcp_forward.py                       # 0.0.0.0:4096 -> 127.0.0.1:49374
    python tcp_forward.py 4096 49374            # 同上，显式指定
    python tcp_forward.py 4096 49374 127.0.0.1  # 目标主机也可以改

四个不能改的地方（都是踩出来的，改动前先看注释）
----------------------------------------------
1. 超时只用在"建连"这一步：create_connection 的 timeout 会**留在 socket 上**，
   而 SSE 心跳是 15 秒一次 —— 留着它必然空闲超时，手机端永远显示"重连中"。
2. 两个方向各起一条线程：SSE 是长连接，串行转发的必死锁。
3. 一边 EOF 只 shutdown(SHUT_WR)：直接 close 会把还没发完的响应截断。
4. accept 抛错不能退：那曾是静默退出点，现在大声记录并重建监听。
"""

import socket
import sys
import threading
import time
from pathlib import Path

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 4096
TARGET_HOST = "127.0.0.1"
TARGET_PORT = 49374
BUF = 65536

# 挂在后台跑的时候没有控制台可看，日志一律落到脚本旁边
LOG = Path(__file__).with_name("tcp_forward.log")


def log(msg):
    line = time.strftime("[%m-%d %H:%M:%S] ") + msg
    print(line, flush=True)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def pipe(src, dst):
    try:
        while True:
            data = src.recv(BUF)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        # 只关写方向：对面可能还有响应没发完，直接 close 会截断它
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client, target):
    try:
        upstream = socket.create_connection(target, timeout=10)
    except OSError as e:
        log("  上游连不上: %s" % e)
        client.close()
        return

    # 建连成功就把超时摘干净，让两个方向都能无限期阻塞在 recv 上
    upstream.settimeout(None)
    client.settimeout(None)

    t1 = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
    t2 = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    log("  - 连接结束")
    for s in (client, upstream):
        try:
            s.close()
        except OSError:
            pass


def make_server(listen):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(listen)
    srv.listen(64)
    return srv


def main():
    listen_port = int(sys.argv[1]) if len(sys.argv) > 1 else LISTEN_PORT
    target_port = int(sys.argv[2]) if len(sys.argv) > 2 else TARGET_PORT
    target_host = sys.argv[3] if len(sys.argv) > 3 else TARGET_HOST
    listen = (LISTEN_HOST, listen_port)
    target = (target_host, target_port)

    srv = make_server(listen)
    log("转发已启动: %s:%d  ->  %s:%d" % (listen + target))
    while True:
        try:
            client, addr = srv.accept()
        except OSError as e:
            # 有东西从外面动监听 socket 时 accept 会抛错。
            # 以前这里直接把进程带没了、日志一片空白；现在重建监听，绝不悄悄退场。
            log("  accept 异常: %r，重建监听" % (e,))
            try:
                srv.close()
            except OSError:
                pass
            while True:
                try:
                    srv = make_server(listen)
                    log("  监听已重建")
                    break
                except OSError as e2:
                    log("  重建失败: %r，1 秒后重试" % (e2,))
                    time.sleep(1)
            continue
        log("  + 来自 %s:%d" % addr)
        threading.Thread(target=handle, args=(client, target), daemon=True).start()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BaseException:
        import traceback

        log("异常退出:\n" + traceback.format_exc())
        raise
