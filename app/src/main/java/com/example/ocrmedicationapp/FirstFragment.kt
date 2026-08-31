package com.example.ocrmedicationapp

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.ocrmedicationapp.databinding.FragmentFirstBinding

class FirstFragment : Fragment(R.layout.fragment_first) {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: PrescriptionViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFirstBinding.bind(view)
        viewModel = ViewModelProvider(this)[PrescriptionViewModel::class.java]

        // viewModel.ocrResult.observe(viewLifecycleOwner) { text ->
        //    binding.resultTextView.text = text
        // }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}