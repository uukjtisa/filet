package dev.niccc2007.filet.desktop.mount

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference

/**
 * Trap handled here: **Windows caps a WebDAV file at 50 MB.**
 *
 * The WebClient service refuses any file larger than `FileSizeLimitInBytes`, default 50,000,000,
 * and the failure looks like a corrupt file or a dropped connection rather than a limit - a copy
 * runs to exactly 50 MB and stops. The fix is one registry value, and a README instruction to
 * edit HKLM is one nobody follows, so the tool reads the value and offers to raise it.
 */
object WebClientLimit {
    const val KEY = "SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters"
    const val VALUE = "FileSizeLimitInBytes"
    const val DEFAULT = 50_000_000L

    /** `0xFFFFFFFF`, the most a DWORD holds - just under 4.3 GB, and as high as Windows goes. */
    const val MAX = 0xFFFF_FFFFL

    sealed interface Verdict {
        data class Fine(val limit: Long) : Verdict
        data class Capped(val limit: Long) : Verdict
        /** No WebClient service at all - Windows Server without the WebDAV redirector feature. */
        data object NoWebClient : Verdict
    }

    /**
     * Pure. [raw] is the DWORD as JNA returns it, signed - so a raised `0xFFFFFFFF` arrives as -1
     * and has to be read unsigned, or the maximum looks like a negative limit and gets flagged.
     * An absent value means the built-in default, not "no limit".
     */
    fun verdict(keyExists: Boolean, raw: Int?): Verdict {
        if (!keyExists) return Verdict.NoWebClient
        val limit = raw?.toUInt()?.toLong() ?: DEFAULT
        return if (limit >= MAX) Verdict.Fine(limit) else Verdict.Capped(limit)
    }

    /** Windows only. Reads HKLM, which any user may do. */
    fun read(): Verdict = runCatching {
        val exists = Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, KEY)
        val raw = if (exists && Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, KEY, VALUE)) {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, KEY, VALUE)
        } else null
        verdict(exists, raw)
    }.getOrElse { Verdict.NoWebClient }

    /**
     * Raise the limit to [MAX] and restart WebClient so it takes effect.
     *
     * Writing HKLM needs elevation and this process does not have it, so this is the one place the
     * tool starts another program: an elevated PowerShell through `ShellExecuteEx` with the
     * `runas` verb, which is what puts up the UAC prompt. Its output is never read - success is
     * decided by reading the registry back afterwards, which is a value and not localised text.
     *
     * Restarting WebClient drops any WebDAV drive that is open at that moment; Windows reconnects
     * remembered ones on next access. Blocking: returns once the elevated process has exited.
     */
    fun raise(): Verdict {
        val script = "New-ItemProperty -Path 'HKLM:\\$KEY' -Name $VALUE -PropertyType DWord -Value 0xFFFFFFFF -Force | Out-Null; " +
            "Restart-Service WebClient -Force"
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            fMask = SEE_MASK_NOCLOSEPROCESS
            lpVerb = "runas"
            lpFile = "powershell.exe"
            lpParameters = "-NoProfile -NonInteractive -WindowStyle Hidden -Command \"$script\""
            nShow = 0
        }
        // False when the UAC prompt was declined. Nothing was written, and the read-back says so.
        if (Shell32.INSTANCE.ShellExecuteEx(info) && info.hProcess != null) {
            Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, 60_000)
            Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, IntByReference())
            Kernel32.INSTANCE.CloseHandle(info.hProcess)
        }
        return read()
    }

    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040

}
