// 보이지 않는 WebView 안에서 마스킹 엔진(서버의 /pwa/mask-host.html)을 돌린다.
// 엔진은 PWA와 같은 masking.js(YuNet + 번호판 t-384 + EfficientDet-Lite0)라서 결과가 PWA·서버 기준과 같다.
// Expo Go에는 ONNX Runtime 네이티브 모듈이 없어서, 앱 빌드 없이 시험하려고 이 방식을 쓴다.
import { forwardRef, useImperativeHandle, useRef } from "react";
import { View } from "react-native";
import { WebView } from "react-native-webview";

const MaskEngine = forwardRef(function MaskEngine({ server, onStatus }, ref) {
  const web = useRef(null);
  const waiting = useRef(new Map());
  const seq = useRef(0);

  useImperativeHandle(ref, () => ({
    // b64: 가리기 전 JPEG. 돌려주는 것: { b64(가린 JPEG), report, boxes, size, timings, blur, maskingVersion }
    mask(b64, appVersion) {
      const id = `m${++seq.current}`;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          waiting.current.delete(id);
          reject(new Error("가리기 시간이 너무 오래 걸립니다"));
        }, 60000);
        waiting.current.set(id, { resolve, reject, timer });
        web.current?.postMessage(JSON.stringify({ type: "mask", id, b64, appVersion }));
      });
    },
    reloadModels() {
      web.current?.postMessage(JSON.stringify({ type: "reload-models" }));
    },
    reload() {
      web.current?.reload();
    },
  }));

  function onMessage(e) {
    let msg;
    try { msg = JSON.parse(e.nativeEvent.data); } catch { return; }
    if (msg.type === "status") return onStatus?.(msg);
    const w = waiting.current.get(msg.id);
    if (!w) return;
    waiting.current.delete(msg.id);
    clearTimeout(w.timer);
    if (msg.type === "masked") w.resolve(msg);
    else w.reject(Object.assign(new Error(msg.message), { name: msg.name || "MaskError" }));
  }

  function failAll(message) {
    for (const [id, w] of waiting.current) {
      clearTimeout(w.timer);
      w.reject(new Error(message));
      waiting.current.delete(id);
    }
    onStatus?.({ ready: false, status: {}, error: message });
  }

  return (
    <View style={{ width: 1, height: 1, position: "absolute", opacity: 0 }} pointerEvents="none">
      <WebView
        ref={web}
        source={{ uri: `${server.replace(/\/+$/, "")}/pwa/mask-host.html` }}
        originWhitelist={["https://*", "http://*"]}
        onMessage={onMessage}
        javaScriptEnabled
        cacheEnabled
        onError={(e) => failAll(`엔진 페이지를 열지 못함: ${e.nativeEvent.description}`)}
        onHttpError={(e) => failAll(`엔진 페이지 HTTP ${e.nativeEvent.statusCode}`)}
        onContentProcessDidTerminate={() => { failAll("엔진이 멈춰서 다시 불러옵니다"); web.current?.reload(); }}
      />
    </View>
  );
});

export default MaskEngine;
