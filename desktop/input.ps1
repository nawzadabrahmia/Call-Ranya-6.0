# UoR Call – remote-support input helper (Windows).
# Reads one command per line from stdin. Only started while the user has ALLOWED control in a support session.
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class UorInput {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int n);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int d, UIntPtr e);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte sc, uint f, UIntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUT {
    [FieldOffset(0)] public uint type;
    [FieldOffset(8)] public MOUSEINPUT mi;
    [FieldOffset(8)] public KEYBDINPUT ki;
  }
  [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
  public static void Move(double x, double y) {
    int w = GetSystemMetrics(0), h = GetSystemMetrics(1);
    SetCursorPos((int)Math.Round(x * (w - 1)), (int)Math.Round(y * (h - 1)));
  }
  public static void Button(int b, bool down) {
    uint f = b == 2 ? (down ? 0x0008u : 0x0010u) : b == 1 ? (down ? 0x0020u : 0x0040u) : (down ? 0x0002u : 0x0004u);
    mouse_event(f, 0, 0, 0, UIntPtr.Zero);
  }
  public static void Wheel(int delta) { mouse_event(0x0800, 0, 0, delta, UIntPtr.Zero); }
  public static void Key(int vk, bool down) {
    uint f = down ? 0u : 0x0002u;
    if (vk == 0x25 || vk == 0x26 || vk == 0x27 || vk == 0x28 || vk == 0x2D || vk == 0x2E || vk == 0x21 || vk == 0x22 || vk == 0x23 || vk == 0x24) f |= 0x0001u;
    keybd_event((byte)vk, 0, f, UIntPtr.Zero);
  }
  public static void Text(string s) {
    foreach (char c in s) {
      INPUT[] a = new INPUT[2];
      a[0].type = 1; a[0].ki.wScan = c; a[0].ki.dwFlags = 0x0004;
      a[1].type = 1; a[1].ki.wScan = c; a[1].ki.dwFlags = 0x0004 | 0x0002;
      SendInput(2, a, Marshal.SizeOf(typeof(INPUT)));
    }
  }
}
"@
[UorInput]::SetProcessDPIAware() | Out-Null
[Console]::Out.WriteLine("READY")
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  $p = $line.Split(' ')
  switch ($p[0]) {
    'M' { [UorInput]::Move([double]::Parse($p[1], [Globalization.CultureInfo]::InvariantCulture), [double]::Parse($p[2], [Globalization.CultureInfo]::InvariantCulture)) }
    'D' { [UorInput]::Button([int]$p[1], $true) }
    'U' { [UorInput]::Button([int]$p[1], $false) }
    'W' { [UorInput]::Wheel([int]$p[1]) }
    'K' { [UorInput]::Key([int]$p[1], $p[2] -eq '1') }
    'T' { [UorInput]::Text([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))) }
    'Q' { exit }
  }
}
