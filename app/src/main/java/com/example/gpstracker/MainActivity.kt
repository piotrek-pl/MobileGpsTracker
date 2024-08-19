package com.example.gpstracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth


class MainActivity : ComponentActivity() {

    private lateinit var mqttClient: Mqtt3AsyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    LoginScreen { username, password ->
                        connectToMqttBroker(username, password)
                    }
                }
            }
        }
    }

    private fun connectToMqttBroker(username: String, password: String) {
        // Tworzenie klienta MQTT
        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .serverHost("51.20.193.191")
            .serverPort(1883)
            .simpleAuth(
                Mqtt3SimpleAuth.builder()
                    .username(username)
                    .password(password.toByteArray())
                    .build()
            )
            .buildAsync()

        // Łączenie się z brokerem MQTT
        mqttClient.connect()
            .whenComplete { connAck, throwable ->
                if (throwable != null) {
                    // Błąd połączenia
                    runOnUiThread {
                        Toast.makeText(this, "Connection failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    }
                    throwable.printStackTrace()
                } else {
                    // Pomyślne połączenie
                    runOnUiThread {
                        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
                    }

                    // Subskrypcja tematu
                    mqttClient.subscribeWith()
                        .topicFilter("example/topic")
                        .callback { publish ->
                            val payload = String(publish.payloadAsBytes)
                            runOnUiThread {
                                Toast.makeText(this, "Received: $payload", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .send()
                }
            }
    }
}

