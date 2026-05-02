package com.sshtool.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.sshtool.BuildConfig
import com.sshtool.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Toast.makeText(requireContext(), "[DEBUG] About onCreateView start", Toast.LENGTH_SHORT).show()
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        Toast.makeText(requireContext(), "[DEBUG] About layout inflated ok", Toast.LENGTH_SHORT).show()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Toast.makeText(requireContext(), "[DEBUG] About onViewCreated start", Toast.LENGTH_SHORT).show()
        
        try {
            binding.toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }
            Toast.makeText(requireContext(), "[DEBUG] About toolbar set", Toast.LENGTH_SHORT).show()

            binding.tvVersion.text = getString(
                com.sshtool.R.string.about_version_format,
                BuildConfig.VERSION_NAME
            )
            Toast.makeText(requireContext(), "[DEBUG] About tvVersion set", Toast.LENGTH_SHORT).show()
            
            binding.tvBuildTime.text = BuildConfig.BUILD_TIME
            binding.tvVersionCode.text = BuildConfig.VERSION_CODE.toString()
            binding.tvGitSha.text = BuildConfig.GIT_SHA
            binding.tvSourceLink.text = BuildConfig.GIT_REPO
            Toast.makeText(requireContext(), "[DEBUG] About text fields set", Toast.LENGTH_SHORT).show()

            binding.tvSourceLink.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.GIT_REPO))
                startActivity(intent)
            }
            Toast.makeText(requireContext(), "[DEBUG] 关于页完成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "[DEBUG] About crash: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
