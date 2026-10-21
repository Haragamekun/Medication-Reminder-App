package com.example.ocrmedicationapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.ocrmedicationapp.databinding.DialogFrequencyCountSelectionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FrequencyCountSelectionSheet(
    private val currentCount: Int = 1,
    private val onFrequencyCountSelected: (count: Int, formattedLabel: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogFrequencyCountSelectionBinding? = null
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setWindowAnimations(R.style.BottomSheetSlideAnimation)
            applyLiquidGlassModalEffect(dimAmount = 0.60f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFrequencyCountSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when (currentCount) {
            2 -> binding.rgFrequencyCount.check(R.id.rbTwice)
            3 -> binding.rgFrequencyCount.check(R.id.rbThrice)
            4 -> binding.rgFrequencyCount.check(R.id.rbFourTimes)
            else -> binding.rgFrequencyCount.check(R.id.rbOnce)
        }

        binding.btnBackFrequencyCount.setOnClickListener {
            dismiss()
        }

        binding.btnSaveFrequencyCount.setOnClickListener {
            val (count, label) = when (binding.rgFrequencyCount.checkedRadioButtonId) {
                R.id.rbTwice -> 2 to "2 times a day"
                R.id.rbThrice -> 3 to "3 times a day"
                R.id.rbFourTimes -> 4 to "4 times a day"
                else -> 1 to "1 time a day"
            }
            onFrequencyCountSelected(count, label)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
