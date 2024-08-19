package com.example.gpstracker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth

class MainActivity : ComponentActivity(), OnMapReadyCallback {

    private lateinit var mqttClient: Mqtt3AsyncClient
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var loginLayout: LinearLayout
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginLayout = findViewById(R.id.loginLayout)
        mapView = findViewById(R.id.mapView)
        usernameField = findViewById(R.id.usernameField)
        passwordField = findViewById(R.id.passwordField)

        // Ustawienie domyślnych wartości
        usernameField.setText("sub1")
        passwordField.setText("a4Yg3u8W")

        val loginButton = findViewById<Button>(R.id.loginButton)

        loginButton.setOnClickListener {
            val username = usernameField.text.toString()
            val password = passwordField.text.toString()
            connectToMqttBroker(username, password)
        }
    }

    private fun connectToMqttBroker(username: String, password: String) {
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

        mqttClient.connect()
            .whenComplete { connAck: Mqtt3ConnAck?, throwable: Throwable? ->
                if (throwable != null) {
                    runOnUiThread {
                        Toast.makeText(this, "Connection failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Connected successfully!", Toast.LENGTH_SHORT).show()
                        // Połączenie udane, przełącz widoki
                        showMap()
                    }
                }
            }
    }

    private fun showMap() {
        loginLayout.visibility = LinearLayout.GONE
        mapView.visibility = MapView.VISIBLE

        mapView.onCreate(null)
        mapView.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("Marker"))
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 2f))
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) {
            mapView.onDestroy()
        }
        mqttClient.disconnect()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }
}
