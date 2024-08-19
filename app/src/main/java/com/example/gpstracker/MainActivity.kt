package com.example.gpstracker

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), OnMapReadyCallback {

    private lateinit var mqttClient: Mqtt3AsyncClient
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var loginLayout: LinearLayout
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var messageTextView: TextView
    private var marker: Marker? = null

    private val connectionTimeout = 5 // Timeout in seconds
    private var lastMessageTime = System.currentTimeMillis()

    private var isDisconnected = false
    private var isFirstMessageReceived = false

    private val handler = Handler(Looper.getMainLooper())
    private val checkConnectionRunnable = object : Runnable {
        override fun run() {
            checkConnectionStatus()
            handler.postDelayed(this, 1000) // Check every second
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginLayout = findViewById(R.id.loginLayout)
        mapView = findViewById(R.id.mapView)
        usernameField = findViewById(R.id.usernameField)
        passwordField = findViewById(R.id.passwordField)
        messageTextView = findViewById(R.id.messageTextView)

        // Ustawienie domyślnych wartości
        usernameField.setText("sub1")
        passwordField.setText("a4Yg3u8W")

        val loginButton = findViewById<Button>(R.id.loginButton)

        loginButton.setOnClickListener {
            val username = usernameField.text.toString()
            val password = passwordField.text.toString()
            connectToMqttBroker(username, password)
        }

        // Obsługa kliknięcia przycisku zmiany typu mapy
        val mapTypeButton = findViewById<Button>(R.id.mapTypeButton)
        mapTypeButton.setOnClickListener {
            showMapTypeDialog()
        }

        // MapView Initialization
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
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
                        subscribeToLocationTopic()
                        handler.post(checkConnectionRunnable) // Start connection checking
                    }
                }
            }
    }

    private fun subscribeToLocationTopic() {
        val topic = "client1/gps/location"
        mqttClient.subscribeWith()
            .topicFilter(topic)
            .callback { publish: Mqtt3Publish ->
                val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                handleIncomingLocationData(payload)
            }
            .send()
    }

    private fun handleIncomingLocationData(payload: String) {
        runOnUiThread {
            val data = payload.split(",")

            if (data.size >= 4) {
                val latitude = data[2].toDoubleOrNull()
                val longitude = data[3].toDoubleOrNull()

                if (latitude != null && longitude != null) {
                    lastMessageTime = System.currentTimeMillis() // Update last message time
                    isFirstMessageReceived = true // First message received

                    if (latitude == 99.0 && longitude == 99.0) {
                        showUnknownLocationMessage()
                    } else {
                        hideMessage() // Hide any message if location is known
                        updateMap(latitude, longitude)
                    }
                } else {
                    showUnknownLocationMessage()
                }
            }
        }
    }

    private fun checkConnectionStatus() {
        val currentTime = System.currentTimeMillis()
        if (isFirstMessageReceived && currentTime - lastMessageTime > connectionTimeout * 1000) {
            if (!isDisconnected) {
                showDisconnectedMessage()
                isDisconnected = true
            }
        } else {
            isDisconnected = false
        }
    }

    private fun showMap() {
        loginLayout.visibility = LinearLayout.GONE
        mapView.visibility = MapView.VISIBLE

        // Pokaż przycisk zmiany typu mapy
        val mapTypeButton = findViewById<Button>(R.id.mapTypeButton)
        mapTypeButton.visibility = Button.VISIBLE

        mapView.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Ustawienie domyślnej pozycji mapy
        val initialPosition = LatLng(51.812859, 19.501045)  // Możesz zmienić na domyślną pozycję
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 14f))

        // Włączenie przycisków do zmiany typu mapy i warstw
        googleMap.uiSettings.isMapToolbarEnabled = true
        googleMap.uiSettings.isZoomControlsEnabled = false // Wyłączenie przycisków zoom
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true

        // Sprawdzenie uprawnień do lokalizacji
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        } else {
            // Poproś o uprawnienia, jeśli nie zostały przyznane
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun showMapTypeDialog() {
        val mapTypes = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
        val selectedType = when (googleMap.mapType) {
            GoogleMap.MAP_TYPE_SATELLITE -> 1
            GoogleMap.MAP_TYPE_TERRAIN -> 2
            GoogleMap.MAP_TYPE_HYBRID -> 3
            else -> 0
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Map Type")
        builder.setSingleChoiceItems(mapTypes, selectedType) { dialog, which ->
            googleMap.mapType = when (which) {
                1 -> GoogleMap.MAP_TYPE_SATELLITE
                2 -> GoogleMap.MAP_TYPE_TERRAIN
                3 -> GoogleMap.MAP_TYPE_HYBRID
                else -> GoogleMap.MAP_TYPE_NORMAL
            }
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun updateMap(lat: Double, lng: Double) {
        val newPosition = LatLng(lat, lng)

        if (marker == null) {
            marker = googleMap.addMarker(MarkerOptions().position(newPosition))
        } else {
            marker?.position = newPosition
        }

        googleMap.moveCamera(CameraUpdateFactory.newLatLng(newPosition))
    }

    private fun showDisconnectedMessage() {
        messageTextView.text = "Connection lost!"
        messageTextView.visibility = TextView.VISIBLE
    }

    private fun showUnknownLocationMessage() {
        messageTextView.text = "Unknown location!"
        messageTextView.visibility = TextView.VISIBLE
    }

    private fun hideMessage() {
        messageTextView.visibility = TextView.GONE
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
        handler.removeCallbacks(checkConnectionRunnable)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }
}
