package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.databinding.FragmentCreateKeywordGroupBinding
import neth.iecal.curbox.utils.KeywordFileCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.*

class CreateKeywordGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_keyword_group"
        private const val FILE_BUFFER_SIZE = 64 * 1024
        private const val KEYWORD_LIST_URL = "https://github.com/curbox-app/website_packs"
    }

    private var _binding: FragmentCreateKeywordGroupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var selectedKeywords = linkedSetOf<String>()
    private val keywordAdapter by lazy { KeywordAdapter() }
    private var isEditing = false
    private var existingGroupId: String? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importKeywordsFromFile(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let { exportKeywordsToFile(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateKeywordGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.rvKeywords.adapter = keywordAdapter
        
        existingGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        
        if (existingGroupId != null) {
            loadExistingGroup(existingGroupId!!)
        }

        setupListeners()
    }

    private fun loadExistingGroup(groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                val group = config.keywordGroups.find { it.id == groupId }
                if (group != null && !isEditing) {
                    val (keywords, keywordItems) = withContext(Dispatchers.Default) {
                        val uniqueKeywords = group.selectedKeywords.toCollection(LinkedHashSet())
                        uniqueKeywords to uniqueKeywords.toList()
                    }
                    isEditing = true
                    binding.tvTitle.text = getString(R.string.keyword_group_edit_title)
                    binding.etGroupName.setText(group.name)
                    selectedKeywords = keywords
                    keywordAdapter.submitList(keywordItems)

                    group.config?.let { scheduledConfig ->
                        viewModel.currentTimeConfig = scheduledConfig.schedule
                        viewModel.currentUsageConfig = scheduledConfig.usage
                    }

                    viewModel.warningScrnConfig = group.warningScreenConfig
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty() && selectedKeywords.add(kw)) {
                updateKeywordsList()
                binding.etKeyword.setText("")
            }
        }

        binding.btnConfigureSchedule.setOnClickListener {
            KeywordTimeBasedSettingsFragment().show(
                parentFragmentManager,
                KeywordTimeBasedSettingsFragment.FRAGMENT_ID
            )
        }
        binding.btnConfigureUsage.setOnClickListener {
            KeywordUsageBasedSettingsFragment().show(
                parentFragmentManager,
                KeywordUsageBasedSettingsFragment.FRAGMENT_ID
            )
        }

        binding.btnConfigureWarningScreen.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = existingGroupId == null
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            bundle.getString("result_config")?.let {
                viewModel.warningScrnConfig = Gson().fromJson(it, AppBlockerWarningScreenConfig::class.java)
            }
        }

        binding.btnMoreOptions.setOnClickListener {
            showMoreOptions(it)
        }

        binding.fabSaveGroup.setOnClickListener { saveGroup() }
    }

    private fun showMoreOptions(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_group_options, popup.menu)
        
        if (existingGroupId == null) {
            popup.menu.findItem(R.id.action_delete)?.isVisible = false
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    importLauncher.launch("text/plain")
                    true
                }
                R.id.action_export -> {
                    val fileName = "keywords_${binding.etGroupName.text.toString().ifEmpty { "group" }}.txt"
                    exportLauncher.launch(fileName)
                    true
                }
                R.id.action_download -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEYWORD_LIST_URL)))
                    true
                }
                R.id.action_delete -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        existingGroupId?.let { viewModel.deleteGroupAndWait(it) }
                        requireActivity().finish()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun importKeywordsFromFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val existingKeywords = selectedKeywords.toHashSet()
                val newKeywords = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(
                            InputStreamReader(inputStream, Charsets.UTF_8),
                            FILE_BUFFER_SIZE
                        ).use { reader ->
                            KeywordFileCodec.readNewKeywords(reader, existingKeywords)
                        }
                    } ?: throw IllegalStateException("Unable to open keyword file")
                }

                val addedCount = newKeywords.count { selectedKeywords.add(it) }
                if (addedCount > 0) {
                    updateKeywordsList()
                    Toast.makeText(requireContext(), getString(R.string.keyword_imported_count, addedCount), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.keyword_no_new_import, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.keyword_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportKeywordsToFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val keywords = selectedKeywords.toList()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        BufferedWriter(
                            OutputStreamWriter(outputStream, Charsets.UTF_8),
                            FILE_BUFFER_SIZE
                        ).use { writer ->
                            KeywordFileCodec.writeKeywords(writer, keywords)
                        }
                    } ?: throw IllegalStateException("Unable to open keyword file")
                }
                Toast.makeText(requireContext(), R.string.keyword_export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.keyword_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateKeywordsList() {
        keywordAdapter.submitList(selectedKeywords.toList())
    }

    inner class KeywordAdapter : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        private var items = listOf<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: android.widget.TextView = view.findViewById(R.id.tv_keyword)
            val btnRemove: android.widget.ImageButton = view.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val keyword = items[position]
            holder.tvKeyword.text = keyword
            holder.btnRemove.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    selectedKeywords.remove(items[currentPos])
                    updateKeywordsList()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = getString(R.string.group_name_required)
            return
        }
        if (selectedKeywords.isEmpty()) {
            Toast.makeText(requireContext(), R.string.keyword_add_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }

        val existing = viewModel.keywordBlockerConfig.value.keywordGroups.find { it.id == existingGroupId }

        val group = KeywordGroup(
            id = existingGroupId ?: UUID.randomUUID().toString(),
            name = name,
            selectedKeywords = selectedKeywords.toList(),
            config = ScheduledUsageConfig(
                schedule = viewModel.currentTimeConfig,
                usage = viewModel.currentUsageConfig
            ),
            isActive = existing?.isActive ?: true,
            temporarilyDisabledUntilMs = existing?.temporarilyDisabledUntilMs ?: 0L,
            warningScreenConfig = viewModel.warningScrnConfig.copy(isOnOpenConfig = false)
        )

        binding.fabSaveGroup.isEnabled = false
        binding.fabSaveGroup.setText(R.string.settings_saving)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (existingGroupId != null) viewModel.updateGroupByIdAndWait(group)
                else viewModel.addGroupAndWait(group)
                requireActivity().finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(requireContext().applicationContext).logNonFatalError(e)
                Toast.makeText(requireContext(), R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.fabSaveGroup?.apply {
                    isEnabled = true
                    setText(R.string.done)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
