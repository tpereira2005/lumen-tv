"""Sonda MQTT da Hisense (so leitura): liga-se ao broker da TV, escuta e faz consultas de estado.
Nao envia teclas nem altera definicoes.
Uso: python hisense_mqtt_probe.py 192.168.1.82 [cert.pem key.pkcs8]
"""
import ssl
import sys
import time

import paho.mqtt.client as mqtt

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.82"
CERT, KEY = (sys.argv[2], sys.argv[3]) if len(sys.argv) > 3 else (None, None)
PORT = 36669
USER, PASS = "hisenseservice", "multimqttservice"
CLIENT = "tvlauncher-probe"

QUERIES = [
    f"/remoteapp/tv/ui_service/{CLIENT}/actions/sourcelist",
    f"/remoteapp/tv/platform_service/{CLIENT}/actions/getvolume",
    f"/remoteapp/tv/ui_service/{CLIENT}/actions/gettvstate",
    f"/remoteapp/tv/platform_service/{CLIENT}/data/getdeviceinfo",
]


def attempt(use_tls):
    label = "TLS" if use_tls else "sem TLS"
    result = {"rc": None, "msgs": []}
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=CLIENT)
    c.username_pw_set(USER, PASS)
    if use_tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        if CERT:
            ctx.load_cert_chain(CERT, KEY)
        c.tls_set_context(ctx)

    def on_connect(client, _u, _f, rc, _p):
        result["rc"] = str(rc)
        if not rc.is_failure:
            client.subscribe("#")
            for t in QUERIES:
                client.publish(t, "")

    def on_message(_c, _u, msg):
        result["msgs"].append((msg.topic, msg.payload[:400].decode("utf-8", "replace")))

    c.on_connect = on_connect
    c.on_message = on_message
    try:
        c.connect(HOST, PORT, 10)
    except Exception as e:  # noqa: BLE001
        print(f"[{label}] falhou a ligar: {e!r}")
        return False
    c.loop_start()
    time.sleep(8)
    c.loop_stop()
    c.disconnect()
    print(f"[{label}] resultado da ligacao: {result['rc']}; mensagens: {len(result['msgs'])}")
    for topic, payload in result["msgs"][:40]:
        print(f"  {topic}\n    {payload}")
    return result["rc"] == "Success"


if CERT or not attempt(False):
    attempt(True)
