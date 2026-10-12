package com.sshtool.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.databinding.ActivityMainBinding
import com.sshtool.ui.terminal.TerminalViewModel
import com.sshtool.ui.terminal.TerminalViewModelFactory

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val terminalViewModelDelegate = viewModels<TerminalViewModel> {
        TerminalViewModelFactory(SSHToolApp.instance.hostRepository)
    }
    private var terminalViewModelWasRequested = false

    /** The one activity-owned terminal coordinator used by every destination. */
    val terminalViewModel: TerminalViewModel
        get() {
            terminalViewModelWasRequested = true
            return terminalViewModelDelegate.value
        }

    /**
     * Host-list deletion must not instantiate an SSH manager when this
     * activity has never opened a terminal or file-transfer destination. The
     * explicit flag is saved across Activity recreation; accessing restricted
     * ViewModelStore internals here would make lifecycle upgrades unsafe.
     */
    fun existingTerminalViewModelOrNull(): TerminalViewModel? =
        terminalViewModelDelegate.takeIf { terminalViewModelWasRequested }?.value
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        terminalViewModelWasRequested = savedInstanceState?.getBoolean(
            STATE_TERMINAL_VIEW_MODEL_REQUESTED
        ) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: throw IllegalStateException("NavHostFragment not found — check nav_graph.xml")
        navController = navHostFragment.navController
    }

    override fun onStart() {
        super.onStart()
        SSHToolApp.instance.keepAliveController.setForeground(true)
    }

    override fun onStop() {
        SSHToolApp.instance.keepAliveController.setForeground(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_TERMINAL_VIEW_MODEL_REQUESTED,
            terminalViewModelWasRequested
        )
        super.onSaveInstanceState(outState)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private companion object {
        const val STATE_TERMINAL_VIEW_MODEL_REQUESTED = "terminal_view_model_requested"
    }
}
