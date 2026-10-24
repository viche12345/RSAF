/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.chiller3.rsaf.BaseActivity
import com.chiller3.rsaf.ui.theme.AppTheme

class SyncJobsActivity : BaseActivity() {
    companion object {
        fun createIntent(context: Context) = Intent(context, SyncJobsActivity::class.java)
    }

    @Composable
    override fun ActivityContent() {
        AppTheme {
            SyncJobsScreen(onBack = ::finish)
        }
    }
}
