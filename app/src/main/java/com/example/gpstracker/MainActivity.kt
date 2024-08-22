package com.example.gpstracker

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private val connectionTimeout = 6000 // Timeout in seconds
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

    // Nowe zmienne klasy dla username i password
    private lateinit var username: String
    private lateinit var password: String

    // Dodanie NetworkCallback do monitorowania zmian w stanie sieci
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

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
            username = usernameField.text.toString()
            password = passwordField.text.toString()
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

        // Inicjalizacja ConnectivityManager i NetworkCallback
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (::mqttClient.isInitialized && !mqttClient.state.isConnected) {
                    reconnectToMqttBroker()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Toast.makeText(this@MainActivity, "Network connection lost", Toast.LENGTH_SHORT).show()
            }
        }

        // Rejestracja NetworkCallback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun connectToMqttBroker(username: String, password: String) {
        mqttClient = MqttClient.builder()
            .useMqttVersion3()  // Użycie wersji MQTT 3.0
            .serverHost("51.20.193.191")
            .serverPort(1883)
            .simpleAuth(
                Mqtt3SimpleAuth.builder()
                    .username(username)
                    .password(password.toByteArray())
                    .build()
            )
            .automaticReconnectWithDefaultConfig() // Automatyczne ponowne łączenie
            .buildAsync()  // Tworzenie asynchronicznego klienta

        mqttClient.connect()
            .whenComplete { _: Mqtt3ConnAck?, throwable: Throwable? ->
                if (throwable != null) {
                    runOnUiThread {
                        Toast.makeText(this, "Connection failed: ${throwable.message}. Retrying...", Toast.LENGTH_LONG).show()
                    }
                    retryConnection(username, password)
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

    private fun reconnectToMqttBroker() {
        mqttClient.connect()
            .whenComplete { _: Mqtt3ConnAck?, throwable: Throwable? ->
                if (throwable != null) {
                    runOnUiThread {
                        Toast.makeText(this, "Reconnection failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    }
                    retryConnection(username, password) // Użycie zmiennych klasy
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Reconnected successfully!", Toast.LENGTH_SHORT).show()
                        // Połączenie udane, przełącz widoki
                        showMap()
                        subscribeToLocationTopic()
                        handler.post(checkConnectionRunnable) // Start connection checking
                    }
                }
            }
    }

    // Metoda do ponownej próby połączenia w przypadku błędu
    private fun retryConnection(username: String, password: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            connectToMqttBroker(username, password)
        }, 5000) // Próba ponownego połączenia po 5 sekundach
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
        Log.d("GPS", "Received payload: $payload")

        runOnUiThread {
            val data = payload.split(",")

            if (data.size >= 4) {
                val latitude = data[2].toDoubleOrNull()
                val longitude = data[3].toDoubleOrNull()

                Log.d("GPS", "Parsed latitude: $latitude, longitude: $longitude")

                if (latitude != null && longitude != null) {
                    lastMessageTime = System.currentTimeMillis() // Zaktualizuj czas ostatniej wiadomości
                    isFirstMessageReceived = true // Pierwsza wiadomość została odebrana

                    Log.d("GPS", "Message received. lastMessageTime updated: $lastMessageTime")

                    // Ukryj komunikat "connection lost", jeśli przychodzi nowa lokalizacja
                    hideMessage()
                    isDisconnected = false // Zresetuj flagę
                    Log.d("GPS", "Connection restored, isDisconnected: $isDisconnected")

                    if (latitude == 99.0 && longitude == 99.0) {
                        showUnknownLocationMessage()
                        Log.d("GPS", "Unknown location received.")
                    } else {
                        updateMap(latitude, longitude)
                        Log.d("GPS", "Map updated with new location.")
                    }
                } else {
                    showUnknownLocationMessage()
                    Log.d("GPS", "Invalid location data.")
                }
            }
        }
    }

    private fun checkConnectionStatus() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastMessage = currentTime - lastMessageTime

        Log.d("GPS", "Checking connection status. Current time: $currentTime, Last message time: $lastMessageTime, Time since last message: $timeSinceLastMessage ms, isDisconnected: $isDisconnected")

        if (isFirstMessageReceived && timeSinceLastMessage > connectionTimeout) {
            if (!isDisconnected) {
                showDisconnectedMessage()
                isDisconnected = true
                Log.d("GPS", "Connection lost. Showing disconnect message.")
            }
        } else if (isDisconnected && timeSinceLastMessage <= connectionTimeout) {
            hideMessage()
            isDisconnected = false
            Log.d("GPS", "Connection restored within timeout. Hiding disconnect message.")
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
        googleMap.uiSettings.isMyLocationButtonEnabled = false

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
        /*if (::mqttClient.isInitialized && !mqttClient.state.isConnected) {
            reconnectToMqttBroker()
        }*/
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
        connectivityManager.unregisterNetworkCallback(networkCallback) // Odrejestruj NetworkCallback
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }
}
