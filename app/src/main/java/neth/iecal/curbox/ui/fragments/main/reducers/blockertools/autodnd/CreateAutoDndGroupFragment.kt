package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AutoDndGroup
import neth.iecal.curbox.databinding.FragmentCreateAutodndGroupBinding
import java.util.UUID

class CreateAutoDndGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_autodnd_group"
    }

    private var _binding: FragmentCreateAutodndGroupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AutoDndViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAutodndGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var isEditing = false
        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")

        if (groupId == null) {
            viewModel.currentTimeConfig = AppTimeConfig()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.groupId == groupId }
                    if (group != null && !isEditing) {
                        isEditing = true
                        binding.textView.text = getString(R.string.autodnd_group_edit_title)
                        binding.etGroupName.setText(group.groupName)

                        binding.btnDeleteGroup.visibility = View.VISIBLE
                        binding.btnDeleteGroup.setOnClickListener {
                            viewLifecycleOwner.lifecycleScope.launch {
                                viewModel.removeGroupAndWait(group)
                                Toast.makeText(requireContext(), R.string.group_deleted, Toast.LENGTH_SHORT).show()
                                requireActivity().finish()
                            }
                        }

                        viewModel.currentTimeConfig = group.timeConfig.copy()
                    }
                }
            }
        }

        binding.btnConfigureSchedule.setOnClickListener {
            AutoDndTimeSettingsFragment().show(parentFragmentManager, AutoDndTimeSettingsFragment.FRAGMENT_ID)
        }

        binding.fabSaveGroup.setOnClickListener {
            val name = binding.etGroupName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etGroupName.error = "Please enter a group name"
                return@setOnClickListener
            }
            
            // Auto DND always turns on DND
            val autoTurnOnDnd = true

            // Check for DND access before saving
            if (!neth.iecal.curbox.utils.DndHelper.ensureDndAccess(requireContext())) {
                return@setOnClickListener
            }

            val savedGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
            val isEditingRecord = savedGroupId != null
            val targetExistingGroup = viewModel.groups.value.find { it.groupId == savedGroupId }

            val newGroup = AutoDndGroup(
                groupId = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.groupId else UUID.randomUUID().toString(),
                groupName = name,
                autoTurnOnDnd = autoTurnOnDnd,
                timeConfig = viewModel.currentTimeConfig
            )

            binding.fabSaveGroup.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (isEditingRecord && targetExistingGroup != null) {
                        viewModel.updateGroupAndWait(newGroup)
                    } else {
                        viewModel.addGroupAndWait(newGroup)
                    }

                    Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CrashLogger(requireContext().applicationContext).logNonFatalError(e)
                    Toast.makeText(requireContext(), R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    _binding?.fabSaveGroup?.isEnabled = true
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
