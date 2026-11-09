using System;
using System.Diagnostics;
using System.IO;
using System.Text;

// Build as winexe. Task Scheduler therefore creates no console for this host.
// The original limited-user PowerShell task arguments and exit code are retained.
internal static class BaiduLoginHiddenLauncher
{
    private static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char character in value)
        {
            if (character == '\\')
            {
                backslashes++;
                continue;
            }
            if (character == '"')
            {
                result.Append('\\', backslashes * 2 + 1);
                result.Append('"');
            }
            else
            {
                result.Append('\\', backslashes);
                result.Append(character);
            }
            backslashes = 0;
        }
        result.Append('\\', backslashes * 2);
        return result.Append('"').ToString();
    }

    private static int Main(string[] arguments)
    {
        if (arguments.Length == 0) return 1;
        try
        {
            var command = new StringBuilder();
            foreach (string argument in arguments)
            {
                if (command.Length != 0) command.Append(' ');
                command.Append(Quote(argument));
            }
            var start = new ProcessStartInfo
            {
                FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                    "WindowsPowerShell", "v1.0", "powershell.exe"),
                Arguments = command.ToString(),
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (var child = Process.Start(start))
            {
                if (child == null) return 1;
                child.WaitForExit();
                return child.ExitCode;
            }
        }
        catch
        {
            // Scheduled-task result reports failure; no raw arguments or errors are logged.
            return 1;
        }
    }
}
