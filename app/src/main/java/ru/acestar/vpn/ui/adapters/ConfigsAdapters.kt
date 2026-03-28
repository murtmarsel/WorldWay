// ui/adapters/ConfigsAdapter.kt
package ru.acestar.vpn.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserConfigFile

class ConfigsAdapter(
    private val onItemClick: (UserConfigFile) -> Unit
) : RecyclerView.Adapter<ConfigsAdapter.ViewHolder>() {

    private var configs = listOf<UserConfigFile>()

    fun submitList(newList: List<UserConfigFile>) {
        configs = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        holder.bind(config)
        holder.itemView.setOnClickListener { onItemClick(config) }
    }

    override fun getItemCount() = configs.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvConfigName: TextView = itemView.findViewById(R.id.tvConfigName)
        private val tvConfigType: TextView = itemView.findViewById(R.id.tvConfigType)
        private val tvConfigSize: TextView = itemView.findViewById(R.id.tvConfigSize)

        fun bind(config: UserConfigFile) {
            tvConfigName.text = config.name
            tvConfigType.text = "Тип: ${config.type}"
            val sizeKB = config.size / 1024
            tvConfigSize.text = if (sizeKB > 1024) {
                "Размер: ${sizeKB / 1024} MB"
            } else {
                "Размер: $sizeKB KB"
            }
        }
    }
}