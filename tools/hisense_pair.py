"""Emparelha o Lumen com a TV Hisense (MQTT, porta 36669) e testa comandos.

  python hisense_pair.py pedir            -> a TV mostra um PIN de 4 digitos
  python hisense_pair.py codigo 1234      -> envia o PIN e confirma
  python hisense_pair.py tecla KEY_VOLUMEUP
  python hisense_pair.py volume 12        -> volume absoluto (0-100)
  python hisense_pair.py estado

A identidade (DEVICE) tem de ser a mesma que o Lumen usa (TvRemote.kt), porque a TV autoriza por identidade.
"""
import json
import pathlib
import ssl
import sys
import time

import paho.mqtt.client as mqtt

HOST, PORT = "192.168.1.82", 36669
USER, PASS = "hisenseservice", "multimqttservice"
DEVICE = "02:4C:55:4D:45:4E$normal"  # "LUMEN" num MAC administrado localmente
CERTS = pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/assets/hisense"


def run(publish_topic, payload, wait=6.0):
    msgs = []
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="lumen-pc")
    c.username_pw_set(USER, PASS)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.load_cert_chain(CERTS / "rcm_certchain_pem.cer", CERTS / "rcm_pem_privkey.pkcs8")
    c.tls_set_context(ctx)

    def on_connect(client, _u, _f, rc, _p):
        client.subscribe(f"/remoteapp/mobile/{DEVICE}/#")
        client.subscribe("/remoteapp/mobile/broadcast/#")
        client.publish(publish_topic, payload)

    c.on_connect = on_connect
    c.on_message = lambda _c, _u, m: msgs.append((m.topic, m.payload.decode("utf-8", "replace")[:300]))
    c.connect(HOST, PORT, 10)
    c.loop_start()
    time.sleep(wait)
    c.loop_stop()
    c.disconnect()
    for t, p in msgs:
        print(f"{t}\n    {p}")
    return msgs


def topic(service, action):
    return f"/remoteapp/tv/{service}/{DEVICE}/actions/{action}"


cmd = sys.argv[1] if len(sys.argv) > 1 else "estado"
if cmd == "pedir":
    run(topic("ui_service", "gettvstate"), "")
elif cmd == "codigo":
    run(topic("ui_service", "authenticationcode"), json.dumps({"authNum": sys.argv[2]}))
elif cmd == "tecla":
    run(topic("remote_service", "sendkey"), sys.argv[2], wait=3)
elif cmd == "entrada":  # ex.: entrada 4 HDMI1
    run(topic("ui_service", "changesource"), json.dumps({"sourceid": sys.argv[2], "sourcename": sys.argv[3]}), wait=3)
elif cmd == "volume":
    run(topic("platform_service", "changevolume"), sys.argv[2], wait=3)
elif cmd == "estado":
    run(topic("platform_service", "getvolume"), "", wait=3)
    run(topic("ui_service", "sourcelist"), "", wait=3)
