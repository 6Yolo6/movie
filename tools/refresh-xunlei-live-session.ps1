param(
    [switch]$ProbeOnly,
    [bool]$AllowSavedPasswordLogin = $true,
    [ValidateRange(3, 60)][int]$WaitSeconds = 20
)

$ErrorActionPreference = 'Stop'

function Test-XunleiOrigin([string]$Address) {
    if ([string]::IsNullOrWhiteSpace($Address)) { return $false }
    if ($Address -notmatch '^[a-zA-Z][a-zA-Z0-9+.-]*://') { $Address = 'https://' + $Address }
    $uri = $null
    return [Uri]::TryCreate($Address, [UriKind]::Absolute, [ref]$uri) -and
        $uri.Scheme -eq 'https' -and $uri.Host -eq 'pan.xunlei.com' -and
        $uri.Port -eq 443 -and [string]::IsNullOrEmpty($uri.UserInfo)
}

function Get-SelectedTab($Root) {
    $condition = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::TabItem)
    foreach ($tab in $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)) {
        try {
            $pattern = $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            if ($pattern.Current.IsSelected) { return $tab }
        } catch {}
    }
    return $null
}

function Test-TrustedLivePage($Root, $TargetTab) {
    try {
        $selection = $TargetTab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
        if (-not $selection.Current.IsSelected) { return $false }
        $condition = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::Edit)
        foreach ($edit in $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)) {
            # Browser chrome only; never read any page username/password input.
            if ($edit.Current.Name -in @('地址和搜索栏','Address and search bar') -and
                $edit.Current.AutomationId -like 'view_*') {
                $pattern = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                return Test-XunleiOrigin $pattern.Current.Value
            }
        }
    } catch {}
    return $false
}

function Find-VisibleNamedElement($Root, [string[]]$Names) {
    foreach ($name in $Names) {
        $condition = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $name)
        foreach ($item in $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $condition)) {
            if (-not $item.Current.IsOffscreen -and $item.Current.IsEnabled) { return $item }
        }
    }
    return $null
}

function Invoke-SafeElement($Element) {
    if ($null -eq $Element) { return $false }
    try {
        $invoke = $Element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $invoke.Invoke(); return $true
    } catch {
        try {
            $selection = $Element.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            $selection.Select(); return $true
        } catch { return $false }
    }
}

function Invoke-XunleiLiveRefresh {
    param([scriptblock]$BeforeLoginSubmit)
    $result = [ordered]@{status='needs_live_tab';refreshed=$false;loginSubmitted=$false;needsInteraction=$false}
    $passwordModeSelected = $false; $loginAttempted = $false
    $originalTab = $null; $targetTab = $null; $root = $null; $window = [IntPtr]::Zero; $switched = $false
    try {
        Add-Type -AssemblyName UIAutomationClient
        Add-Type -AssemblyName UIAutomationTypes
        if (-not ('GYingWindowState' -as [type])) {
            Add-Type 'using System; using System.Runtime.InteropServices; public static class GYingWindowState { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); }'
        }
        $tabCondition = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::TabItem)
        foreach ($process in @(Get-Process msedge -ErrorAction SilentlyContinue)) {
            if ($process.MainWindowHandle -eq 0) { continue }
            $candidateRoot = [System.Windows.Automation.AutomationElement]::FromHandle($process.MainWindowHandle)
            foreach ($tab in $candidateRoot.FindAll([System.Windows.Automation.TreeScope]::Descendants, $tabCondition)) {
                if ($tab.Current.Name -notmatch '迅雷云盘|迅雷网盘|Xunlei') { continue }
                $selection = $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
                if (-not $selection.Current.IsSelected -and
                    [GYingWindowState]::GetForegroundWindow() -eq $process.MainWindowHandle) {
                    $result.status = 'busy_other_tab'; continue
                }
                $root = $candidateRoot; $targetTab = $tab; $window = $process.MainWindowHandle
                $originalTab = Get-SelectedTab $root
                if (-not $selection.Current.IsSelected) {
                    if ($ProbeOnly) { $result.status='background_tab_available'; return $result }
                    $selection.Select(); $switched=$true; Start-Sleep -Milliseconds 500
                }
                break
            }
            if ($null -ne $targetTab) { break }
        }
        if ($null -eq $targetTab) { $result.needsInteraction=$true; return $result }
        if (-not (Test-TrustedLivePage $root $targetTab)) {
            $result.status='untrusted_or_changed_tab'; $result.needsInteraction=$true; return $result
        }
        if ($ProbeOnly) { $result.status='ready'; return $result }
        $reload = Find-VisibleNamedElement $root @('刷新','Reload')
        if ($null -eq $reload -or $reload.Current.AutomationId -notlike 'view_*' -or
            -not (Test-TrustedLivePage $root $targetTab)) {
            $result.status='reload_unavailable'; return $result
        }
        if (-not (Invoke-SafeElement $reload)) { $result.status='reload_unavailable'; return $result }
        $result.refreshed=$true; $result.status='refreshed'
        Start-Sleep -Seconds 3
        $deadline=(Get-Date).AddSeconds($WaitSeconds)
        while ((Get-Date) -lt $deadline) {
            if (-not (Test-TrustedLivePage $root $targetTab)) { $result.status='tab_changed'; return $result }
            $documentCondition = New-Object System.Windows.Automation.PropertyCondition(
                [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
                [System.Windows.Automation.ControlType]::Document)
            $pageRoot = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $documentCondition)
            if ($null -eq $pageRoot -or $pageRoot.Current.IsOffscreen) { Start-Sleep -Seconds 1; continue }
            $challenge=Find-VisibleNamedElement $pageRoot @('安全验证','请完成验证','请拖动滑块','请输入验证码','请输入短信验证码')
            if ($null -ne $challenge) { $result.status='verification_required'; $result.needsInteraction=$true; return $result }
            # Let the normal browser password manager autofill. We never read password values,
            # extract Login Data, type passwords or attempt to solve verification challenges.
            if ($AllowSavedPasswordLogin -and -not $loginAttempted) {
                $passwordMode=Find-VisibleNamedElement $pageRoot @('密码登录','账号密码登录','帐号密码登录','Password login')
                if ($null -ne $passwordMode -and -not $passwordModeSelected) {
                    if (-not (Test-TrustedLivePage $root $targetTab)) { return $result }
                    $passwordModeSelected = Invoke-SafeElement $passwordMode
                    Start-Sleep -Milliseconds 700
                }
                $passwordCondition=New-Object System.Windows.Automation.PropertyCondition(
                    [System.Windows.Automation.AutomationElement]::IsPasswordProperty,$true)
                $visiblePassword=$false
                foreach($input in $pageRoot.FindAll([System.Windows.Automation.TreeScope]::Descendants,$passwordCondition)) {
                    if(-not $input.Current.IsOffscreen -and $input.Current.IsEnabled){$visiblePassword=$true;break}
                }
                if($visiblePassword) {
                    $login=Find-VisibleNamedElement $pageRoot @('登录','登 录','立即登录','Login','Sign in')
                    if($null -ne $login -and (Test-TrustedLivePage $root $targetTab)) {
                        $loginAttempted = $true
                        if ($BeforeLoginSubmit) { & $BeforeLoginSubmit }
                        if(Invoke-SafeElement $login){$result.loginSubmitted=$true;$result.status='login_submitted'}
                        else {$result.status='login_submit_unavailable';$result.needsInteraction=$true;return $result}
                    }
                }
            }
            $loginError=Find-VisibleNamedElement $pageRoot @('账号或密码错误','帐号或密码错误','密码错误','登录失败')
            if($null -ne $loginError){$result.status='saved_login_failed';$result.needsInteraction=$true;return $result}
            Start-Sleep -Seconds 1
        }
        return $result
    } catch {
        $result.status='live_browser_unavailable';$result.needsInteraction=$true;return $result
    } finally {
        # Never steal focus or undo a tab change made by the user during this refresh.
        if($switched -and $null -ne $originalTab -and $null -ne $targetTab -and
            [GYingWindowState]::GetForegroundWindow() -ne $window) {
            try {
                $selection=$targetTab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
                if($selection.Current.IsSelected){$originalTab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()}
            } catch {}
        }
    }
}

if($MyInvocation.InvocationName -ne '.') {
    Invoke-XunleiLiveRefresh | ConvertTo-Json -Compress
}
