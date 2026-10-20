"""Build pinned gomobile from verified module source with a path-only reproducibility patch."""
import hashlib,json,os,pathlib,shutil,subprocess
root=pathlib.Path(__file__).resolve().parent.parent
version="v0.0.0-20260908204917-8b95e45f8d3e"
native=root/"engine-reference/gv-native"
tools=root/"fdroid/.build-tools"; tools.mkdir(exist_ok=True)
tool_env=os.environ.copy(); tool_env["GOMODCACHE"]=str(tools/"modcache")
def run(args,cwd): return subprocess.check_output(args,cwd=cwd,env=tool_env,text=True)
assert run(["go","env","GOVERSION"],native).strip()=="go1.27.2"
info=json.loads(run(["go","mod","download","-json","golang.org/x/mobile@"+version],native))
expected=next(line.split()[2] for line in (native/"go.sum").read_text().splitlines() if line.startswith("golang.org/x/mobile "+version+" "))
assert info["Sum"]==expected and info["Version"]==version
source=tools/"mobile"
if source.exists(): shutil.rmtree(source)
shutil.copytree(info["Dir"],source)
for directory,_,_ in os.walk(source): os.chmod(directory,0o755)
target=source/"cmd/gomobile/bind.go"; os.chmod(target,0o644)
original=target.read_text()
needle="\t\tbs, err := f.Format()"
replacement="""\t\t// GV reproducibility: retain local module provenance using relative paths.
\t\t// TMPDIR is inside the checkout, so the same topology works at any root.
\t\tfor _, replacement := range f.Replace {
\t\t\tif replacement.New.Version == "" && filepath.IsAbs(replacement.New.Path) {
\t\t\t\trelative, err := filepath.Rel(dir, replacement.New.Path)
\t\t\t\tif err != nil { return err }
\t\t\t\tif !strings.HasPrefix(relative, ".") { relative = "./" + relative }
\t\t\t\tif err := f.AddReplace(replacement.Old.Path, replacement.Old.Version, filepath.ToSlash(relative), ""); err != nil { return err }
\t\t\t}
\t\t}
\t\tbs, err := f.Format()"""
assert original.count(needle)==1
target.write_text(original.replace(needle,replacement))
subprocess.run(["gofmt","-w",str(target)],check=True)
bin_dir=tools/"bin"; bin_dir.mkdir(exist_ok=True)
for binary in ("gomobile","gobind"):
 subprocess.run(["go","build","-trimpath","-o",str(bin_dir/binary),"./cmd/"+binary],cwd=source,env=tool_env,check=True)
receipt={"module":"golang.org/x/mobile","version":version,"moduleSum":info["Sum"],"originalBindGoSHA256":hashlib.sha256(original.encode()).hexdigest(),"patchedBindGoSHA256":hashlib.sha256(target.read_bytes()).hexdigest(),"change":"Absolute local module replacement paths become relative; module names and versions remain present."}
(tools/"provenance.json").write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt))

