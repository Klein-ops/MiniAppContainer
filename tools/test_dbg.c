#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include "wasm3.h"
#include "m3_env.h"

static unsigned char* read_file(const char* path, size_t* out_len) {
    FILE* f = fopen(path, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* b = malloc(sz); fread(b,1,sz,f); fclose(f); *out_len=sz; return b;
}

int main(int argc, char** argv) {
    const char* path = argc>1?argv[1]:"sample.wasm";
    size_t len; unsigned char* bytes = read_file(path,&len);
    if(!bytes){printf("read fail\n");return 1;}
    IM3Environment env = m3_NewEnvironment();
    IM3Runtime rt = m3_NewRuntime(env, 64*1024, NULL);
    IM3Module mod=NULL;
    M3Result r = m3_ParseModule(env,&mod,bytes,(uint32_t)len);
    printf("parse: %p (msg=%s)\n",(void*)r, r?r:"(ok)");
    if(r){return 2;}
    M3Module* m = (M3Module*)mod;
    printf("after parse: numFunctions=%u\n", m->numFunctions);
    for(u32 i=0;i<m->numFunctions;i++){
        M3Function* f=&m->functions[i];
        printf("  func[%u] numNames=%d", i, f->numNames);
        for(int j=0;j<f->numNames;j++) printf(" name[%d]=%s", j, f->names[j]?f->names[j]:"(null)");
        printf("\n");
    }
    r = m3_LoadModule(rt, mod);
    printf("load: %p (msg=%s)\n",(void*)r, r?r:"(ok)");
    if(r){return 3;}
    printf("after load: runtime->modules=%p\n",(void*)rt->modules);
    IM3Function fn=NULL;
    r = m3_FindFunction(&fn, rt, "add");
    printf("find add: %p fn=%p (msg=%s)\n",(void*)r,(void*)fn, r?r:"(ok)");
    m3_FreeRuntime(rt);
    free(bytes);
    return 0;
}
