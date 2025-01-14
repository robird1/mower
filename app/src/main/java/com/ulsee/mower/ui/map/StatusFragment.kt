package com.ulsee.mower.ui.map

import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.jarvislau.destureviewbinder.GestureViewBinder
import com.ulsee.mower.App
import com.ulsee.mower.MainActivity
import com.ulsee.mower.R
import com.ulsee.mower.ble.BluetoothLeRepository
import com.ulsee.mower.ble.BluetoothLeService
import com.ulsee.mower.data.BLEBroadcastAction
import com.ulsee.mower.data.MapData
import com.ulsee.mower.data.StartStop.Command.Companion.BACK_CHARGING_STATION
import com.ulsee.mower.data.StartStop.Command.Companion.PAUSE_MOWING
import com.ulsee.mower.data.StartStop.Command.Companion.RESUME_EMERGENCY_STOP
import com.ulsee.mower.data.StartStop.Command.Companion.RESUME_FROM_INTERRUPT
import com.ulsee.mower.data.StartStop.Command.Companion.RESUME_FROM_STUCK
import com.ulsee.mower.data.StartStop.Command.Companion.RESUME_MOWING
import com.ulsee.mower.data.StartStop.Command.Companion.START_MOWING
import com.ulsee.mower.data.Status.*
import com.ulsee.mower.data.Status.WorkingMode.Companion.LEARNING_MODE
import com.ulsee.mower.data.Status.WorkingMode.Companion.MANUAL_MODE
import com.ulsee.mower.data.Status.WorkingMode.Companion.SUSPEND_WORKING_MODE
import com.ulsee.mower.data.Status.WorkingMode.Companion.TESTING_BOUNDARY_MODE
import com.ulsee.mower.data.Status.WorkingMode.Companion.WORKING_MODE
import com.ulsee.mower.data.StatusFragmentBroadcast
import com.ulsee.mower.databinding.ActivityStatusBinding
import com.ulsee.mower.ui.connect.RobotListFragmentArgs
import com.ulsee.mower.ui.login.LoginActivity

private val TAG = StatusFragment::class.java.simpleName

class StatusFragment: Fragment() {
    private lateinit var binding: ActivityStatusBinding
    lateinit var viewModel: StatusFragmentViewModel
    private lateinit var bluetoothService: BluetoothLeService
    private var isReceiverRegistered = false
    private var signalQuality = -1
    private var isMowingStatus = false
    private var workingMode = MANUAL_MODE
//    private var estimatedTime = ""

    private var state = MowingState.Stop
        set(value) {
            field = value
            when (value) {
                MowingState.Mowing -> context?.sendBroadcast(Intent(StatusFragmentBroadcast.MOWER_STATUS_MOWING))
                MowingState.Pause -> context?.sendBroadcast(Intent(StatusFragmentBroadcast.MOWER_STATUS_PAUSE))
                MowingState.Stop -> context?.sendBroadcast(Intent(StatusFragmentBroadcast.MOWER_STATUS_STOP))
            }
        }
    enum class MowingState {
        Mowing, Pause, Stop
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "[Enter] onCreate")
        super.onCreate(savedInstanceState)
        checkService()
        initViewModel()
    }

    override fun onResume() {
        super.onResume()
        context?.sendBroadcast(Intent(StatusFragmentBroadcast.LIFECYCLE_ONRESUME))
    }

    override fun onPause() {
        context?.sendBroadcast(Intent(StatusFragmentBroadcast.LIFECYCLE_ONPAUSE))
        super.onPause()
    }

    private fun checkService() {
        (requireActivity().application as App).bluetoothService?.let {
            bluetoothService = it

        } ?: run {
            val intent = Intent(requireContext(), LoginActivity::class.java)
            startActivity(intent)
            requireActivity().finish()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.d(TAG, "[Enter] onCreateView")

        binding = ActivityStatusBinding.inflate(inflater, container, false)

        GestureViewBinder.bind(requireContext(), binding.rootLayout, binding.statusView)
//        GestureViewBinder.setFullGroup(true)

        addOnBackPressedCallback()
        initSetupButtonListener()
        initParkingButtonListener()
        initStartButtonListener()
        initPauseButtonListener()
        initScheduleButton()
        initSettingButton()
        initEditMapButton()
        registerBLEReceiver()
        viewModel.getStatusPeriodically()
//        getMapData()

        val args = StatusFragmentArgs.fromBundle(requireArguments())
        Log.d("123", "args.refreshMap: ${args.refreshMap}")
        if (args.refreshMap) {
            getMapData()
        }
        observerMainActivityAWSMqttStatus(binding.root)

        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initStatusObserver()
        initStartStopObserver()
        initMapDataObserver()
        initGlobalParameterObserver()
        initMowingDataObserver()
        initGattConnectedStatus()
    }

    override fun onDestroyView() {
        Log.d(TAG, "[Enter] onDestroyView")
        if (isReceiverRegistered) {
            requireActivity().unregisterReceiver(viewModel.gattUpdateReceiver)
            isReceiverRegistered = false
        }

        requireArguments().clear()

        super.onDestroyView()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(requireActivity(), StatusFragmentFactory(
            BluetoothLeRepository(bluetoothService))).get(StatusFragmentViewModel::class.java)
    }

    fun observerMainActivityAWSMqttStatus(view: View) {
        val statusView = view.findViewById<View>(R.id.view_aws_status_view)
        val isConnected = (requireActivity() as MainActivity).viewModel?.isAWSMqttManagerConnected?.value == true
        statusView?.setBackgroundColor(requireContext().getColor(if (isConnected)android.R.color.holo_green_light else android.R.color.darker_gray))
        (requireActivity() as MainActivity).viewModel?.isAWSMqttManagerConnected?.observe(viewLifecycleOwner, {
            statusView?.setBackgroundColor(requireContext().getColor(if (it)android.R.color.holo_green_light else android.R.color.darker_gray))
        })
    }

    private fun getMapData() {
        binding.progressView.isVisible = true
        viewModel.getMapGlobalParameters()
    }

    private fun addOnBackPressedCallback() {
        activity?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.progressView.isVisible) {
                        binding.progressView.isVisible = false
                        return
                    }

                    val dialog = AlertDialog.Builder(context)
                    dialog.setMessage("Disconnect and return to device list page ?")
                        .setCancelable(false)
                        .setPositiveButton(R.string.button_confirm) { _, _ ->

                            MapData.clear()
                            viewModel.disconnectDevice()
                            findNavController().popBackStack()

                        }
                        .setNegativeButton("cancel") { it, _ ->
                            it.dismiss()
                        }
                        .show()
                }
            })
    }

    private fun registerBLEReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter()
            filter.addAction(BLEBroadcastAction.ACTION_STATUS)
            filter.addAction(BLEBroadcastAction.ACTION_START_STOP)
            filter.addAction(BLEBroadcastAction.ACTION_GLOBAL_PARAMETER)
            filter.addAction(BLEBroadcastAction.ACTION_GRASS_BOARDER)
            filter.addAction(BLEBroadcastAction.ACTION_OBSTACLE_BOARDER)
            filter.addAction(BLEBroadcastAction.ACTION_GRASS_PATH)
            filter.addAction(BLEBroadcastAction.ACTION_CHARGING_PATH)
            filter.addAction(BLEBroadcastAction.ACTION_MOWING_DATA)
            filter.addAction(BLEBroadcastAction.ACTION_GATT_CONNECTED)
            filter.addAction(BLEBroadcastAction.ACTION_GATT_NOT_SUCCESS)
            filter.addAction(BLEBroadcastAction.ACTION_VERIFICATION_SUCCESS)
            filter.addAction(BLEBroadcastAction.ACTION_VERIFICATION_FAILED)
            requireActivity().registerReceiver(viewModel.gattUpdateReceiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun initMapDataObserver() {
        viewModel.hasMapData.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { result ->
                if (result) {
                    binding.constraintLayoutNoMap.isVisible = false
                    binding.constraintLayoutCustomView.isVisible = true

                } else {
                    binding.progressView.isVisible = false

                    binding.constraintLayoutNoMap.isVisible = true
                    binding.constraintLayoutCustomView.isVisible = false
                }
            }
        }
    }

    private fun initGlobalParameterObserver() {
//        viewModel.requestMapFinished.observe(viewLifecycleOwner) {
        RequestMapAction.requestMapFinished.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { isFinished ->
                binding.progressView.isVisible = false
                if (isFinished) {
                    binding.statusView.initData()
                }
            }
        }
    }

    private fun initMowingDataObserver() {
        viewModel.mowingDataList.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { hashMap ->
//                Log.d("666", "[Enter] updateMowingArea() size: ${it.size} isMowingStatus: $isMowingStatus")

                // 刀盤啟動中或作業暫停中
                if (isMowingStatus || workingMode == SUSPEND_WORKING_MODE) {
                    binding.statusView.updateMowingArea(hashMap)
                }
            }
        }
    }

    private fun initGattConnectedStatus() {
        viewModel.gattConnected.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { isConnected ->
                if (isConnected) {
                    binding.connectedView.isVisible = true
                    binding.disconnectedView.isVisible = false
                    binding.connectStausText.text = "Connected"
                } else {
                    binding.connectedView.isVisible = false
                    binding.disconnectedView.isVisible = true
                    binding.connectStausText.text = "Disconnected"
                }
            }
        }
    }

    private fun initStartStopObserver() {
        viewModel.startStopResult.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { pair ->
                val isSuccess = pair.first
                val command = pair.second
                if (isSuccess) {
                    if (command == "0") {    // 開始作業
                        viewModel.workingErrorCodeList.clear()
                    }
                }
            }
        }
    }

    private fun initStatusObserver() {
        viewModel.statusIntent.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { intent ->
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 0)
                val angle = intent.getFloatExtra("angle", 0F)
                val power = intent.getIntExtra("power", -1)
                workingMode = intent.getIntExtra("working_mode", -1)
                val errorCode = intent.getIntExtra("working_error_code", -1)
                val robotStatus = intent.getStringExtra("robot_status") ?: ""
                val isCharging = intent.getBooleanExtra("charging_status", false)
                isMowingStatus = intent.getBooleanExtra("mowing_status", false)
                val interruptionCode = intent.getStringExtra("interruption_code") ?: ""
                signalQuality = intent.getIntExtra("signal_quality", -1)

                setWorkingAreaText(intent)
                setPowerPercentage(power)
//                setChargingText(isCharging)
                setBatteryView(isCharging)
                checkSatelliteSignal()
                binding.statusView.notifyRobotCoordinate(x, y, angle)
                checkWorkingErrorCode(errorCode)
                checkRobotStatus(robotStatus)
                checkInterruptionCode(interruptionCode)
                checkWorkingMode(workingMode)
            }
        }
    }

    private fun setPowerPercentage(power: Int) {
        binding.powerPercentage.text = "$power %"
    }

    private fun setWorkingAreaText(intent: Intent) {
        val totalArea = intent.getShortExtra("total_area", -1)
        val finishedArea = intent.getShortExtra("finished_area", -1)
        binding.workingAreaText.text = "$finishedArea㎡ / $totalArea㎡"

        if (totalArea.toInt() != 0) {
            binding.workingTimeText.text = getMowingPercentage(totalArea, finishedArea)
        }
    }

    private fun getMowingPercentage(totalArea: Short, finishedArea: Short): String {
        return "${Math.round(finishedArea.toFloat() / totalArea * 100)}%"
    }

    private fun checkSatelliteSignal() {
        when (signalQuality) {
            1 -> {
                binding.signalStatus.text = "Available"
                binding.signalViewGood.isVisible = true
                binding.signalViewBad.isVisible = false
            }
            0 -> {
                binding.signalStatus.text = "Unavailable"
                binding.signalViewGood.isVisible = false
                binding.signalViewBad.isVisible = true
            }
        }
    }

    private fun checkWorkingMode(workingMode: Int) {
        when (workingMode) {
            WORKING_MODE, LEARNING_MODE -> {
//                Log.d("777", "[Enter] WORKING_MODE, LEARNING_MODE")
                binding.startMowingBtn.isVisible = false
                binding.startText.isVisible = false
                binding.pauseButton.isVisible = true
                binding.pauseText.isVisible = true
                binding.pauseText.text = "Pause"
                state = MowingState.Mowing

                viewModel.getMowingData(0x00)

            }
            SUSPEND_WORKING_MODE -> {
//                Log.d("777", "[Enter] SUSPEND_WORKING_MODE")
                binding.startMowingBtn.isVisible = false
                binding.startText.isVisible = false
                binding.pauseButton.isVisible = true
                binding.pauseText.isVisible = true
                binding.pauseText.text = "Resume"
                state = MowingState.Pause

//                viewModel.cancelGetMowingData()
                viewModel.getMowingData(0x00)

            }
            MANUAL_MODE -> {
//                Log.d("777", "[Enter] MANUAL_MODE")
                binding.startMowingBtn.isVisible = true
                binding.startText.isVisible = true
                binding.pauseButton.isVisible = false
                binding.pauseText.isVisible = false
                state = MowingState.Stop

                viewModel.cancelGetMowingData()
            }
            TESTING_BOUNDARY_MODE -> {
//                Log.d("777", "[Enter] TESTING_BOUNDARY_MODE")
                // TODO
            }
        }
    }

    private fun checkInterruptionCode(code: String) {
        code.forEachIndexed { idx, value ->
            if (value == '1' && !viewModel.interruptionIdxList.contains(idx)) {
//                val message = Interruption.map[idx] ?: "unknown error"
                val message = Interruption.map[idx]
                if (message != null) {
                    showInterruptionDialog(message, idx)
                    viewModel.interruptionIdxList.add(idx)
                }
            }
        }
    }

    private fun checkRobotStatus(code: String) {
        code.forEachIndexed { idx, value ->
            if (value == '1' && !viewModel.emergencyStopIdxList.contains(idx)) {
                val message = RobotStatus.map[idx] ?: "unknown error"
                showEmergencyStopDialog(message, idx)
                viewModel.emergencyStopIdxList.add(idx)
            }
        }
    }

    private fun checkWorkingErrorCode(code: Int) {
        if (code > 0 && !viewModel.workingErrorCodeList.contains(code)) {
            val message = WorkingErrorCode.map[code] ?: "errorCode: $code"
            showWorkingErrorDialog(message)
            viewModel.workingErrorCodeList.add(code)
        }
    }

    private fun initSetupButtonListener() {
        binding.setupButton.setOnClickListener {
            findNavController().navigate(R.id.instruction0Fragment)
        }
    }

    private fun initSettingButton() {
        binding.settingButton.setOnClickListener {
            findNavController().navigate(R.id.mowerSettingsFragment)
        }
    }

    private fun initEditMapButton() {
        binding.editMapButton.setOnClickListener {
            findNavController().navigate(R.id.setupMapFragment)
        }
    }

    private fun initScheduleButton() {
        binding.scheduleButton.setOnClickListener {
            findNavController().navigate(R.id.scheduleListFragment)
        }
    }

    private fun initStartButtonListener() {
        binding.startMowingBtn.setOnClickListener {
            if (signalQuality == 0) {
                Toast.makeText(context, "Weak satellite signal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.startStop(START_MOWING)
        }
    }

    private fun initPauseButtonListener() {
        binding.pauseButton.setOnClickListener {
            if (signalQuality == 0) {
                Toast.makeText(context, "Weak satellite signal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when (state) {
                MowingState.Mowing -> {
                    viewModel.startStop(PAUSE_MOWING)
                }
                MowingState.Pause -> {
                    viewModel.startStop(RESUME_MOWING)
                }
            }
        }
    }

    private fun initParkingButtonListener() {
        binding.parkingButton.setOnClickListener {
            if (signalQuality == 0) {
                Toast.makeText(context, "Weak satellite signal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when (state) {
                MowingState.Pause -> {
                    showParkingDialog()
                }
                MowingState.Mowing -> {
                    showParkingDialog()
                }
                MowingState.Stop -> {
                    showParkingErrorDialog()
                }
            }
        }
    }

    private fun showParkingDialog() {
        val dialog = AlertDialog.Builder(context)
            .setMessage("Cancel current task and go back to charging station?")
            .setCancelable(false)
            .setPositiveButton("ok") { it, _ ->
                when (state) {
                    MowingState.Pause -> {
                        viewModel.startStop(BACK_CHARGING_STATION)
                        viewModel.startStop(RESUME_MOWING)
                    }
                    MowingState.Mowing -> {
                        viewModel.startStop(BACK_CHARGING_STATION)
                    }
                }
                it.dismiss()
            }
            .setNegativeButton("cancel") { it, _ ->
                it.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun showParkingErrorDialog() {
        val dialog = AlertDialog.Builder(context)
            .setMessage("[Error] Mower is only allowed to return to the charging station when it is mowing.")
            .setCancelable(false)
            .setPositiveButton("ok") { it, _ ->
                it.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun showWorkingErrorDialog(message: String) {
        val dialog = AlertDialog.Builder(context)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("ok") { it, _ ->
                it.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun showEmergencyStopDialog(message: String, bitIndex: Int) {
        val dialog = AlertDialog.Builder(context)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Reset") { it, _ ->
                if (bitIndex != 2) {     // 2 -> 受困
                    viewModel.startStop(RESUME_EMERGENCY_STOP)
                } else {
                    viewModel.startStop(RESUME_FROM_STUCK)
                }
                viewModel.emergencyStopIdxList.remove(bitIndex)
                it.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun showInterruptionDialog(message: String, bitIndex: Int) {
        val dialog = AlertDialog.Builder(context)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Reset") { it, _ ->
                viewModel.startStop(RESUME_FROM_INTERRUPT)
                viewModel.interruptionIdxList.remove(bitIndex)
                it.dismiss()
            }
            .create()
        dialog.show()
    }

//    private fun setChargingText(isCharging: Boolean) {
//        binding.chargingTxt.isVisible = isCharging
//    }

    private fun setBatteryView(isCharging: Boolean) {
        if (!isCharging) {
            binding.batteryView.isVisible = true
            binding.batteryViewCharging.isVisible = false
        } else {
            binding.batteryView.isVisible = false
            binding.batteryViewCharging.isVisible = true
        }
    }

}