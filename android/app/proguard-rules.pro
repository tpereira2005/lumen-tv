# Paho MQTT carrega os módulos de rede (ssl://, tcp://) por ServiceLoader/reflexão.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
