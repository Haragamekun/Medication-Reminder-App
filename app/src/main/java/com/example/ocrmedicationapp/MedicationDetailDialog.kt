package com.example.ocrmedicationapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import coil.load
import com.example.ocrmedicationapp.databinding.DialogMedicationDetailBinding

class MedicationDetailDialog : DialogFragment() {

    private var _binding: DialogMedicationDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setWindowAnimations(android.R.style.Animation_InputMethod)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogMedicationDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val name = arguments?.getString("name") ?: "Unknown"
        val imageUrl = arguments?.getString("imageUrl")
        
        binding.tvDetailName.text = name
        binding.tvDetailForm.text = arguments?.getString("form") ?: "N/A"
        binding.tvDetailIndication.text = arguments?.getString("indication") ?: "N/A"
        binding.tvDetailAdultDosage.text = arguments?.getString("adultDosage") ?: "N/A"
        binding.tvDetailPediatricDosage.text = arguments?.getString("pediatricDosage") ?: "N/A"
        binding.tvDetailRoute.text = arguments?.getString("route") ?: "N/A"
        binding.tvDetailTiming.text = arguments?.getString("timing") ?: "N/A"
        binding.tvDetailSideEffects.text = arguments?.getString("sideEffects") ?: "N/A"
        binding.tvDetailContra.text = arguments?.getString("contraindications") ?: "N/A"
        binding.tvDetailDescription.text = arguments?.getString("description") ?: "No description available."
        binding.tvDetailFrequency.text = arguments?.getString("frequency") ?: "As prescribed"
        binding.tvDetailWarnings.text = arguments?.getString("warnings") ?: "N/A"
        binding.tvDetailInteractions.text = arguments?.getString("interactions") ?: "N/A"
        binding.tvDetailStorage.text = arguments?.getString("storage") ?: "N/A"
        
        imageUrl?.let {
            binding.ivDetailPhoto.load(it) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
            }
        }

        binding.btnBack.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(info: MedInfo): MedicationDetailDialog {
            return MedicationDetailDialog().apply {
                arguments = Bundle().apply {
                    putString("name", info.name)
                    putString("description", info.description)
                    putString("form", info.form)
                    putString("indication", info.indication)
                    putString("adultDosage", info.adultDosage)
                    putString("pediatricDosage", info.pediatricDosage)
                    putString("frequency", info.frequency)
                    putString("route", info.route)
                    putString("timing", info.timing)
                    putString("sideEffects", info.sideEffects)
                    putString("contraindications", info.contraindications)
                    putString("warnings", info.warnings)
                    putString("interactions", info.interactions)
                    putString("storage", info.storage)
                    putString("imageUrl", info.imageUrl)
                }
            }
        }
    }
}
