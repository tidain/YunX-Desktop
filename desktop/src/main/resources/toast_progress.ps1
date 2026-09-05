# YunX-Desktop toast progress renderer (ASCII only; all display text arrives via stdin UTF-8)
# stdin protocol (UTF-8, one command per line, pipe-separated):
#   prog|<title>|<percent 0-99>|<line2>|<value string>|<status label>   show/update progress toast
#   done|<title>|<summary line>                                         show completion toast, then exit
#   exit                                                                remove progress toast, then exit
$ErrorActionPreference = 'SilentlyContinue'
try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}
[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
[void][Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime]
[void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime]
$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('YunX.Desktop')
$progTpl = '<toast><visual><binding template="ToastGeneric"><text>{0}</text><text>{2}</text><progress value="{1}" valueStringOverride="{3}" status="{4}"/></binding></visual></toast>'
$doneTpl = '<toast><visual><binding template="ToastGeneric"><text>{0}</text><text>{1}</text></binding></visual></toast>'
while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $p = $line.Split('|')
    $x = New-Object Windows.Data.Xml.Dom.XmlDocument
    if ($p[0] -eq 'exit') { break }
    if ($p[0] -eq 'done') {
        $x.LoadXml($doneTpl -f $p[1], $p[2])
        $t = New-Object Windows.UI.Notifications.ToastNotification($x)
        $t.Tag = 'yunx-dl'; $t.Group = 'yunx'
        $notifier.Show($t)
        break
    }
    if ($p[0] -eq 'prog') {
        $x.LoadXml($progTpl -f $p[1], [int]$p[2], $p[3], $p[4], $p[5])
        $t = New-Object Windows.UI.Notifications.ToastNotification($x)
        $t.Tag = 'yunx-dl'; $t.Group = 'yunx'
        $notifier.Show($t)
    }
}
try {
    [Windows.UI.Notifications.ToastNotificationManager]::History.Remove('yunx-dl', 'yunx', 'YunX.Desktop') | Out-Null
} catch {}
