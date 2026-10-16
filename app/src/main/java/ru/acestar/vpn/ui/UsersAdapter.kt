package ru.acestar.vpn.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.admin.UserAdminData

class UsersAdapter(
    private val onItemClick:   (UserAdminData) -> Unit,
    private val onConfigsClick: ((UserAdminData) -> Unit)? = null,
    private val onStatsClick:   ((UserAdminData) -> Unit)? = null,
    private val onEditClick:    ((UserAdminData) -> Unit)? = null,
    private val onLogsClick:    ((UserAdminData) -> Unit)? = null
) : RecyclerView.Adapter<UsersAdapter.ViewHolder>() {

    private var originalUsers = listOf<UserAdminData>()
    private var filteredUsers = listOf<UserAdminData>()

    fun submitList(newList: List<UserAdminData>) {
        originalUsers = newList
        filteredUsers = newList
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredUsers = if (query.isEmpty()) originalUsers
        else originalUsers.filter {
            it.email.contains(query, ignoreCase = true) ||
            it.id.toString().contains(query)
        }
        notifyDataSetChanged()
    }

    fun filterByTariff(tariff: String?) {
        filteredUsers = if (tariff == null) originalUsers
        else originalUsers.filter { it.tariff == tariff }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = filteredUsers[position]
        holder.bind(user)

        // Клик на всю карточку — открывает полный диалог деталей
        holder.itemView.setOnClickListener { onItemClick(user) }

        // Кнопки быстрого доступа — каждая открывает свой диалог напрямую
        holder.btnViewConfigs.setOnClickListener {
            onConfigsClick?.invoke(user) ?: onItemClick(user)
        }
        holder.btnStats.setOnClickListener {
            onStatsClick?.invoke(user) ?: onItemClick(user)
        }
        holder.btnEdit.setOnClickListener {
            onEditClick?.invoke(user) ?: onItemClick(user)
        }
        holder.btnLogs.setOnClickListener {
            onLogsClick?.invoke(user) ?: onItemClick(user)
        }
    }

    override fun getItemCount() = filteredUsers.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUserId:     TextView  = itemView.findViewById(R.id.tvUserId)
        private val tvUserEmail:  TextView  = itemView.findViewById(R.id.tvUserEmail)
        private val tvUserTariff: TextView  = itemView.findViewById(R.id.tvUserTariff)
        private val tvUserStatus: TextView  = itemView.findViewById(R.id.tvUserStatus)
        val btnViewConfigs: ImageView = itemView.findViewById(R.id.btnViewConfigs)
        val btnStats:       ImageView = itemView.findViewById(R.id.btnStats)
        val btnEdit:        ImageView = itemView.findViewById(R.id.btnEdit)
        val btnLogs:        ImageView = itemView.findViewById(R.id.btnLogs)

        fun bind(user: UserAdminData) {
            tvUserId.text     = "ID: ${user.id}"
            tvUserEmail.text  = user.email
            tvUserTariff.text = "Тариф: ${user.tariff}"
            tvUserStatus.text = if (user.is_active) "● Активен" else "● Заблокирован"
            tvUserStatus.setTextColor(
                if (user.is_active)
                    itemView.context.getColor(android.R.color.holo_green_dark)
                else
                    itemView.context.getColor(android.R.color.holo_red_dark)
            )
        }
    }
}
