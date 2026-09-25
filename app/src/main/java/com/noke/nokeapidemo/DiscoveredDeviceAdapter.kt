package com.noke.nokeapidemo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.noke.nokemobilelibrary.NokeDefines
import com.noke.nokemobilelibrary.NokeDevice
import com.noke.nokeapidemo.databinding.ItemDiscoveredDeviceBinding

/**
 * RecyclerView adapter for displaying discovered Noke BLE devices.
 *
 * Shows each device's name, MAC address, lock state, and signal strength.
 */
class DiscoveredDeviceAdapter(
    private val devices: List<NokeDevice>,
    private val onDeviceClicked: (NokeDevice) -> Unit
) : RecyclerView.Adapter<DiscoveredDeviceAdapter.DeviceViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = devices[position].mac.hashCode().toLong()

    inner class DeviceViewHolder(
        private val binding: ItemDiscoveredDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(noke: NokeDevice) {
            binding.tvDeviceName.text = noke.name.ifBlank { noke.mac }
            binding.tvDeviceMac.text = noke.mac
            binding.tvLockState.text = lockStateLabel(noke.lockState)
            binding.tvDeviceType.text = deviceTypeLabel(noke)
            binding.root.setOnClickListener { onDeviceClicked(noke) }
        }

        private fun lockStateLabel(lockState: Int): String = when (lockState) {
            NokeDefines.NOKE_LOCK_STATE_LOCKED -> "Locked"
            NokeDefines.NOKE_LOCK_STATE_UNLOCKED -> "Unlocked"
            NokeDefines.NOKE_LOCK_STATE_UNSHACKLED -> "Unshackled"
            else -> "Unknown"
        }

        private fun deviceTypeLabel(noke: NokeDevice): String {
            val hw = noke.hardwareVersion?.lowercase() ?: ""
            return when {
                hw.contains("ion2") || hw.contains("i2") -> "ION-2 (Phone Key)"
                hw.contains("f") -> "Fob"
                else -> "Standard"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDiscoveredDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size
}
