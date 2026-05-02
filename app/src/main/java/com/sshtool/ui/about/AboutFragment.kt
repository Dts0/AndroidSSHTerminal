package com.sshtool.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvVersion.text = getString(
            com.sshtool.R.string.about_version_format,
            BuildConfig.VERSION_NAME
        )
        binding.tvBuildTime.text = BuildConfig.BUILD_TIME
        binding.tvVersionCode.text = BuildConfig.VERSION_CODE.toString()
        binding.tvGitSha.text = BuildConfig.GIT_SHA
        binding.tvSourceLink.text = BuildConfig.GIT_REPO

        binding.tvSourceLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.GIT_REPO))
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
