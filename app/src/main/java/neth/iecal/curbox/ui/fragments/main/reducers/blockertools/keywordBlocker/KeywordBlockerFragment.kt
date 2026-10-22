package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TemporaryDisableDialog
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

class KeywordBlockerFragment : Fragment() {

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private val adapter = KeywordGroupAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.activateIfNeeded()
        binding.rvKeywordGroups.layoutManager = LinearLayoutManager(requireContext())
        binding.rvKeywordGroups.adapter = adapter
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }

        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_blocker, popup.menu)

        val config = viewModel.keywordBlockerConfig.value
        popup.menu.findItem(R.id.menu_block_unsupported_browsers).isChecked = config.blockAllExceptSupported

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_block_unsupported_browsers -> {
                    val newValue = !item.isChecked
                    item.isChecked = newValue
                    viewModel.setBlockAllExceptSupported(newValue)
                    true
                }
                R.id.menu_help -> {
                    val url = "https://curbox.app/docs/reducers/keyword-blocker/"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                val isEmpty = config.keywordGroups.isEmpty()
                binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvKeywordGroups.visibility = if (isEmpty) View.GONE else View.VISIBLE
                adapter.submitList(config.keywordGroups)
            }
        }
    }

    inner class KeywordGroupAdapter :
        ListAdapter<KeywordGroup, KeywordGroupAdapter.ViewHolder>(DIFF) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val tvRemaining: TextView = view.findViewById(R.id.tv_group_remaining)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = getItem(position)
            holder.tvName.text = group.name
            holder.tvDetails.text =
                getString(R.string.group_details_keywords_only, group.selectedKeywords.size)

            holder.tvRemaining.visibility = View.GONE
            holder.tvRemaining.tag = group.id
            val temporaryRemaining = group.temporarilyDisabledUntilMs - System.currentTimeMillis()
            if (group.temporarilyDisabledUntilMs ==
                TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED
            ) {
                holder.tvRemaining.text =
                    getString(R.string.temporary_disable_until_manually_enabled)
                holder.tvRemaining.visibility = View.VISIBLE
            } else if (temporaryRemaining > 0L) {
                holder.tvRemaining.text = getString(
                    R.string.temporary_disable_until,
                    SettingsChangeDelayUtils.formatRemaining(requireContext(), temporaryRemaining)
                )
                holder.tvRemaining.visibility = View.VISIBLE
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val remaining = viewModel.getRemainingUsageMillis(group)
                    if (holder.tvRemaining.tag != group.id) return@launch
                    if (remaining == null) {
                        holder.tvRemaining.visibility = View.GONE
                    } else {
                        holder.tvRemaining.text = if (remaining <= 0L) {
                            getString(R.string.group_no_usage_left)
                        } else {
                            getString(
                                R.string.group_usage_left,
                                TimeTools.formatTimeForWidget(remaining)
                            )
                        }
                        holder.tvRemaining.visibility = View.VISIBLE
                    }
                }
            }

            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (!isChecked && group.isActive && viewModel.temporaryDisableAvailable.value) {
                    holder.switchActive.setOnCheckedChangeListener(null)
                    holder.switchActive.isChecked = true
                    holder.switchActive.post {
                        if (pos in 0 until itemCount) notifyItemChanged(pos)
                    }
                    TemporaryDisableDialog.show(
                        this@KeywordBlockerFragment,
                        getString(R.string.temporary_disable_title, group.name)
                    ) { minutes ->
                        viewModel.temporarilyDisableGroup(group.id, minutes)
                    }
                } else {
                    viewModel.updateGroupActiveState(group.id, isChecked)
                }
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.id)
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"

        private val DIFF = object : DiffUtil.ItemCallback<KeywordGroup>() {
            override fun areItemsTheSame(oldItem: KeywordGroup, newItem: KeywordGroup) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: KeywordGroup, newItem: KeywordGroup) =
                oldItem == newItem
        }
    }
}
