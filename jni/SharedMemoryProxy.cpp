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

#define LOG_TAG "SharedMemoryProxy"
#define LOG_NDEBUG 0
#include <log/log.h>

#include <errno.h>
#include <string.h>
#include "SharedMemoryProxy.h"
using namespace android;

SharedMemoryProxy::~SharedMemoryProxy()
{
    if (mData != nullptr) {
        ALOGE("share memory release--B1 %p",this);
        munmap(mData, mSize);
        close(mAshmemFd);
        mAshmemFd = -1;
        mSize = -1;
        mData = nullptr;
    }
}
void SharedMemoryProxy::releaseMem(){
    if (mData != nullptr) {
        ALOGE("share memory release temp%p",this);
        munmap(mData, mSize);
        close(mAshmemFd);
        mAshmemFd = -1;
        mSize = -1;
        mData = nullptr;
    }
}
int SharedMemoryProxy::allocmem(int32_t capacityInBytes) {
    if (mAshmemFd > 0) {
        ALOGE("already allocate");
        return -2;
    }
    ALOGE("share memory allocmem--A %p",this);
    mSize = capacityInBytes;
    mAshmemFd = ashmem_create_region("swapbuffer", capacityInBytes);
    if (mAshmemFd < 0) {
        ALOGE("open() ashmem_create_region() failed %d", errno);
        return -1;
    }
    mData = (uint8_t*)mmap(NULL, mSize, PROT_READ | PROT_WRITE, MAP_SHARED, mAshmemFd, 0);
    if (mData == MAP_FAILED) {
        close(mAshmemFd);
        return -2;
    }

    if (ashmem_set_prot_region(mAshmemFd, PROT_READ) < 0) {
        munmap(mData, mSize);
        close(mAshmemFd);
        return -2;
    }
    return 0;
}
