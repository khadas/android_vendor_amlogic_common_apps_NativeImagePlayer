/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef IMAGEPLAYER_SHARED_MEMORY_PROXY_H
#define IMAGEPLAYER_SHARED_MEMORY_PROXY_H

#include <stdint.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>

namespace android {

class SharedMemoryProxy {
public:
    SharedMemoryProxy() {isInUse = false;}

    ~SharedMemoryProxy();

    int allocmem(int32_t capacityInBytes);

    int getFileDescriptor() const {
        return mAshmemFd;
    }
    size_t getSize(){
        return mSize;
    }
    void setUsed(bool use) {
        isInUse = use;
    }
    bool getIsUsed() {
        return isInUse;
    }
    void releaseMem();
    uint8_t* getmem() {
        return mData;
    }
private:
    bool isInUse;
    int mAshmemFd;
    uint8_t* mData  = nullptr;
    size_t mSize = -1;
};

} /* namespace aaudio */

#endif //IMAGEPLAYER_SHARED_MEMORY_PROXY_H
