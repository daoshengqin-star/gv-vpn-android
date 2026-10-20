from pathlib import Path
import zipfile, struct, json, hashlib
root=Path(__file__).resolve().parent
aar=root/'libv2ray-gv.aar'
results=[]
with zipfile.ZipFile(aar) as archive:
    for name in archive.namelist():
        if not name.endswith('.so'): continue
        data=archive.read(name)
        assert data[:4]==b'\x7fELF' and data[5]==1
        bits=data[4]
        if bits==2:
            offset=struct.unpack_from('<Q',data,32)[0]
            size,count=struct.unpack_from('<HH',data,54)
            headers=[struct.unpack_from('<IIQQQQQQ',data,offset+i*size) for i in range(count)]
            fields=[(h[0],h[3],h[6],h[7]) for h in headers]
        else:
            offset=struct.unpack_from('<I',data,28)[0]
            size,count=struct.unpack_from('<HH',data,42)
            headers=[struct.unpack_from('<IIIIIIII',data,offset+i*size) for i in range(count)]
            fields=[(h[0],h[2],h[5],h[7]) for h in headers]
        loads=[align for kind,addr,mem,align in fields if kind==1]
        relro=[(addr+mem)%16384 for kind,addr,mem,align in fields if kind==0x6474e552]
        passed=all(n>=16384 for n in loads) and bool(relro) and all(n==0 for n in relro)
        results.append(dict(file=name,load_alignment=loads,relro_remainder=relro,aligned16k=passed))
report={'aar_sha256':hashlib.sha256(aar.read_bytes()).hexdigest(),'libraries':results,'passed':all(r['aligned16k'] for r in results)}
(root/'native-alignment-verified.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
assert report['passed'], 'LOAD/RELRO 16KB validation failed'
