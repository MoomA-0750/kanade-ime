# 落とすところを試すための HTTP のサーバー。どの道も、頼まれた名前の大きさ（モデルの表と同じ）の 0 のバイトをゆっくり返す
import http.server, sys, time

SIZES = {"encoder-epoch-35-avg-1.int8.onnx": 70_876_409, "decoder-epoch-35-avg-1.int8.onnx": 1_308_690,
         "joiner-epoch-35-avg-1.int8.onnx": 1_033_417, "tokens.txt": 26_631}


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name = self.path.rsplit("/", 1)[-1]
        size = SIZES.get(name)
        if size is None:
            self.send_error(404)
            return
        # 続きを頼まれたら（Range: bytes=N-）その先だけを 206 で返す
        start = 0
        r = self.headers.get("Range", "")
        if r.startswith("bytes=") and r.endswith("-"):
            start = int(r[6:-1])
        if start:
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
            sys.stderr.write(f"続きから: {name} {start}\n")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(size - start))
        self.end_headers()
        sent = start
        try:
            while sent < size:
                n = min(1 << 20, size - sent)
                self.wfile.write(b"\0" * n)
                sent += n
                time.sleep(0.05)
        except BrokenPipeError:
            pass

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[2])), H).serve_forever()
