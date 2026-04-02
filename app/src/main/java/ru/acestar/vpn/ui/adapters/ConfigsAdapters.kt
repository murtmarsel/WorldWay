// ui/adapters/ConfigsAdapter.kt
package ru.acestar.vpn.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserConfigFile

class ConfigsAdapter(
    private val onOpenContent: (UserConfigFile) -> Unit,
    private val onBackup: (UserConfigFile) -> Unit,
    private val onCertInfo: (UserConfigFile) -> Unit
) : RecyclerView.Adapter<ConfigsAdapter.ViewHolder>() {

    private var configs = listOf<UserConfigFile>()

    fun submitList(newList: List<UserConfigFile>) {
        configs = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_config_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        holder.bind(config, onOpenContent, onBackup, onCertInfo)
    }

    override fun getItemCount() = configs.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProtocolIcon: ImageView = itemView.findViewById(R.id.ivProtocolIcon)
        private val tvConfigName: TextView = itemView.findViewById(R.id.tvConfigName)
        private val tvConfigPath: TextView = itemView.findViewById(R.id.tvConfigPath)
        private val tvConfigSize: TextView = itemView.findViewById(R.id.tvConfigSize)
        private val tvConfigProtocol: TextView = itemView.findViewById(R.id.tvConfigProtocol)
        private val tvConfigCategory: TextView = itemView.findViewById(R.id.tvConfigCategory)
        private val btnOpenContent: ImageView = itemView.findViewById(R.id.btnOpenContent)
        private val btnBackup: ImageView = itemView.findViewById(R.id.btnBackup)
        private val btnCertInfo: ImageView = itemView.findViewById(R.id.btnCertInfo)

        fun bind(
            config: UserConfigFile,
            onOpenContent: (UserConfigFile) -> Unit,
            onBackup: (UserConfigFile) -> Unit,
            onCertInfo: (UserConfigFile) -> Unit
        ) {
            tvConfigName.text = config.name
            tvConfigPath.text = itemView.context.getString(R.string.admin_config_path_label, config.path)
            tvConfigSize.text = itemView.context.getString(
                R.string.admin_config_size_label,
                config.sizeFormatted
            )
            tvConfigProtocol.text = itemView.context.getString(
                R.string.admin_config_type_label,
                protocolLabel(config)
            )
            val category = config.category?.takeIf { it.isNotBlank() }
            tvConfigCategory.text = if (category != null) {
                itemView.context.getString(R.string.admin_config_category_label, category)
            } else {
                itemView.context.getString(R.string.admin_config_category_empty)
            }

            ivProtocolIcon.setImageResource(protocolIconRes(config))
            ivProtocolIcon.contentDescription = itemView.context.getString(
                R.string.admin_config_type_label,
                protocolLabel(config)
            )

            btnOpenContent.setOnClickListener { onOpenContent(config) }
            btnBackup.setOnClickListener { onBackup(config) }
            btnCertInfo.setOnClickListener { onCertInfo(config) }
        }

        private fun protocolLabel(config: UserConfigFile): String {
            val p = config.protocol?.trim()?.lowercase().orEmpty()
            val t = config.type.trim().lowercase()
            return when {
                p.contains("wireguard") || p.contains("wg") -> "WireGuard"
                p.contains("openvpn") || p.contains("ovpn") -> "OpenVPN"
                t.contains("wireguard") || t.contains("wg") -> "WireGuard"
                t.contains("openvpn") || t.contains("ovpn") -> "OpenVPN"
                config.protocol.isNullOrBlank().not() -> config.protocol!!.trim()
                config.type.isNotBlank() -> config.type
                else -> "—"
            }
        }

        private fun protocolIconRes(config: UserConfigFile): Int {
            val p = (config.protocol ?: config.type).lowercase()
            return when {
                p.contains("wireguard") || p.contains("wg") -> R.drawable.ic_wireguard
                p.contains("openvpn") || p.contains("ovpn") -> R.drawable.ic_openvpn
                else -> R.drawable.ic_file
            }
        }
    }
}
