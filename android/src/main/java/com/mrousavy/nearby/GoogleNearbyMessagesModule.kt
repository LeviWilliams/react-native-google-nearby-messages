package com.mrousavy.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.messages.*

class GoogleNearbyMessagesModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {
    private enum class EventType(private val _type: String) {
        MESSAGE_FOUND("MESSAGE_FOUND"), MESSAGE_LOST("MESSAGE_LOST"), BLUETOOTH_ERROR("BLUETOOTH_ERROR"), PERMISSION_ERROR("PERMISSION_ERROR"), MESSAGE_NO_DATA_ERROR("MESSAGE_NO_DATA_ERROR"), UNSUPPORTED_ERROR("UNSUPPORTED_ERROR");

        override fun toString(): String {
            return _type
        }

    }

    private var _messagesClient: MessagesClient? = null
    private var _publishedMessage: Message? = null
    private var _isSubscribed = false
    private var _listener: MessageListener? = null
    private var _subscribeOptions: SubscribeOptions? = null
    private var _publishOptions: PublishOptions? = null
    private val isMinimumAndroidVersion: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
    private val context: Context?
        get() = currentActivity

    private fun isGooglePlayServicesAvailable(showErrorDialog: Boolean): Boolean {
        val googleApi = GoogleApiAvailability.getInstance()
        val availability = googleApi.isGooglePlayServicesAvailable(context)
        val result = availability == ConnectionResult.SUCCESS
        if (!result &&
                showErrorDialog &&
                googleApi.isUserResolvableError(availability)) {
            googleApi.getErrorDialog(currentActivity, availability, PLAY_SERVICES_RESOLUTION_REQUEST).show()
        }
        return result
    }

    @ReactMethod
    fun connect(apiKey: String?, promise: Promise) {
        Log.d(name, "Connecting...")
        if (!isMinimumAndroidVersion) {
            emitErrorEvent(EventType.UNSUPPORTED_ERROR, true, "Current Android version is too low: " + Integer.toString(Build.VERSION.SDK_INT))
            return
        }
        if (!isGooglePlayServicesAvailable(true)) {
            emitErrorEvent(EventType.UNSUPPORTED_ERROR, true, "Google Play Services is not available on this device.")
            return
        }
        _listener = object : MessageListener() {
            override fun onFound(message: Message) {
                Log.d(name, "Message found!")
                this.onFound(message)
            }

            override fun onLost(message: Message) {
                Log.d(name, "Message lost!")
                this.onLost(message)
            }
        }
        val context = context
        _messagesClient = Nearby.getMessagesClient(context!!, MessagesOptions.Builder().setPermissions(NearbyPermissions.BLE).build())
        _subscribeOptions = SubscribeOptions.Builder()
                .setStrategy(Strategy.Builder().zze(NearbyPermissions.BLE).setTtlSeconds(Strategy.TTL_SECONDS_INFINITE).build())
                .setCallback(object : SubscribeCallback() {
                    override fun onExpired() {
                        super.onExpired()
                        Log.i(name, "No longer subscribing")
                        emitErrorEvent(EventType.BLUETOOTH_ERROR, true, "Subscribe expired!")
                    }
                }).build()
        _publishOptions = PublishOptions.Builder()
                .setStrategy(Strategy.Builder().zze(NearbyPermissions.BLE).setTtlSeconds(Strategy.TTL_SECONDS_MAX).build())
                .setCallback(object : PublishCallback() {
                    override fun onExpired() {
                        super.onExpired()
                        Log.i(name, "No longer publishing")
                        emitErrorEvent(EventType.BLUETOOTH_ERROR, true, "Publish expired!")
                    }
                }).build()
        _isSubscribed = false
        promise.resolve(null)
        Log.d(name, "Connected!")
    }

    @ReactMethod
    fun disconnect() {
        _listener = null
        _messagesClient = null
        _subscribeOptions = null
        _publishOptions = null
        _isSubscribed = false
    }

    @ReactMethod
    fun subscribe(promise: Promise) {
        Log.d(name, "Subscribing...")
        if (_messagesClient != null) {
            if (_isSubscribed) {
                promise.reject(Exception("An existing callback is already subscribed to the Google Nearby Messages API! Please unsubscribe before subscribing again!"))
            } else {
                _messagesClient!!.subscribe(_listener!!, _subscribeOptions!!).addOnCompleteListener { task ->
                    val e = task.exception
                    val success = task.isSuccessful
                    Log.d(name, "Subscribed! Successful: $success")
                    if (e != null) {
                        _isSubscribed = false
                        promise.reject(mapApiException(e))
                    } else {
                        _isSubscribed = true
                        promise.resolve(null)
                    }
                }
            }
        } else {
            promise.reject(Exception("The Messages Client was null. Did the GoogleNearbyMessagesModule native constructor fail to execute?"))
        }
    }

    @ReactMethod
    fun unsubscribe(promise: Promise) {
        Log.d(name, "Unsubscribing...")
        if (_messagesClient != null) {
            _messagesClient!!.unsubscribe(_listener!!).addOnCompleteListener { task ->
                Log.d(name, "Unsubscribed!")
                val e = task.exception
                if (e != null) {
                    promise.reject(mapApiException(e))
                } else {
                    _isSubscribed = false
                    promise.resolve(null)
                }
            }
        } else {
            promise.reject(Exception("The Messages Client was null. Did the GoogleNearbyMessagesModule native constructor fail to execute?"))
        }
    }

    @ReactMethod
    fun publish(message: String, promise: Promise) {
        if (_messagesClient != null) {
            if (_publishedMessage != null) {
                promise.reject(Exception("There is an active published message! Call unpublish first!"))
            } else {
                _publishedMessage = Message(message.toByteArray())
                _messagesClient!!.publish(_publishedMessage!!, _publishOptions!!).addOnCompleteListener { task ->
                    val e = task.exception
                    if (e != null) {
                        _publishedMessage = null
                        promise.reject(mapApiException(e))
                    } else {
                        promise.resolve(null)
                    }
                }
            }
        } else {
            promise.reject(Exception("The Messages Client was null. Did the GoogleNearbyMessagesModule native constructor fail to execute?"))
        }
    }

    @ReactMethod
    fun unpublish(promise: Promise) {
        if (_messagesClient != null) {
            if (_publishedMessage != null) {
                _messagesClient!!.unpublish(_publishedMessage!!).addOnCompleteListener { task ->
                    val e = task.exception
                    if (e != null) {
                        promise.reject(mapApiException(e))
                    } else {
                        promise.resolve(null)
                        _publishedMessage = null
                    }
                }
            } else {
                promise.reject(Exception("The last published message was null. Did you publish before calling unpublish?"))
            }
        } else {
            promise.reject(Exception("The Messages Client was null. Did the GoogleNearbyMessagesModule native constructor fail to execute?"))
        }
    }

    @ReactMethod
    fun checkBluetoothPermission(promise: Promise) {
        if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            promise.resolve(true)
        } else {
            promise.resolve(false)
        }
    }

    // Google Nearby Messages API Callbacks
    fun onFound(message: Message) {
        val messageString = String(message.content)
        Log.d(name, "Found message: $messageString")
        emitMessageEvent(EventType.MESSAGE_FOUND, messageString)
    }

    fun onLost(message: Message) {
        val messageString = String(message.content)
        Log.d(name, "Lost message: $messageString")
        emitMessageEvent(EventType.MESSAGE_LOST, messageString)
    }


    // React Native Lifecycle Methods
    override fun onHostResume() {
        Log.d(name, "onHostResume")
    }

    override fun onHostPause() {
        Log.d(name, "onHostPause")
    }

    override fun onHostDestroy() {
        Log.d(name, "onHostDestroy")
        if (_publishedMessage != null) _messagesClient!!.unpublish(_publishedMessage!!)
        if (_isSubscribed) _messagesClient!!.unsubscribe(_listener!!)
        // TODO: Additional cleanup? Is BLE now disabled? who knows
    }

    override fun onCatalystInstanceDestroy() {
        Log.d(name, "onCatalystInstanceDestroy")
    }

    override fun getName(): String {
        return "GoogleNearbyMessages"
    }

    private fun mapApiException(e: Exception): Exception {
        val apiException = if (e is ApiException) e else null
        return if (apiException != null) {
            val descriptor = errorCodeToDescriptor(apiException.statusCode)
            Exception(apiException.statusCode.toString() + ": " + descriptor + ". See: https://developers.google.com/android/reference/com/google/android/gms/nearby/messages/NearbyMessagesStatusCodes")
        } else {
            e
        }
    }

    /**
     * Map API error code to descriptor - See: https://developers.google.com/android/reference/com/google/android/gms/nearby/messages/NearbyMessagesStatusCodes
     * @param errorCode The code to map.
     * @return A descriptor for the error code. Or null.
     */
    private fun errorCodeToDescriptor(errorCode: Int): String {
        return when (errorCode) {
            2802 -> "APP_NOT_OPTED_IN"
            2804 -> "APP_QUOTA_LIMIT_REACHED"
            2821 -> "BLE_ADVERTISING_UNSUPPORTED"
            2822 -> "BLE_SCANNING_UNSUPPORTED"
            2820 -> "BLUETOOTH_OFF"
            2803 -> "DISALLOWED_CALLING_CONTEXT"
            2806 -> "FORBIDDEN"
            2807 -> "MISSING_PERMISSIONS"
            2805 -> "NOT_AUTHORIZED"
            2801 -> "TOO_MANY_PENDING_INTENTS"
            else -> "UNKNOWN_ERROR"
        }
    }

    // React Native Event Emitters
    private fun emitMessageEvent(event: EventType, message: String) {
        val params = Arguments.createMap()
        params.putString("message", message)
        val context = reactApplicationContext
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event.toString(), params)
    }

    private fun emitErrorEvent(event: EventType, hasError: Boolean, message: String?) {
        val params = Arguments.createMap()
        params.putString("hasError", hasError.toString())
        if (message != null) {
            params.putString("message", message)
        }
        val context = reactApplicationContext
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event.toString(), params)
    }

    companion object {
        private const val PLAY_SERVICES_RESOLUTION_REQUEST = 9000
    }

    init {
        reactContext.addLifecycleEventListener(this)
    }
}