// 包路径声明
package com.example.utils;

// 导入相关依赖
import com.example.entity.BaseDetail;
import com.example.entity.ConnectionConfig;
import com.example.entity.RuntimeDetail;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Slf4j
@Component
public class MonitorUtils {

    @Lazy  // 延迟加载配置
    @Resource  // 注入连接配置
    ConnectionConfig config;

    // 系统信息采集对象
    private final SystemInfo info = new SystemInfo();
    // 系统属性
    private final Properties properties = System.getProperties();

    // 获取系统基础信息
    public BaseDetail monitorBaseDetail() {
        OperatingSystem os = info.getOperatingSystem();  // 获取操作系统信息
        HardwareAbstractionLayer hardware = info.getHardware();  // 获取硬件抽象层
        
        // 计算内存总量（转换为GB）
        double memory = hardware.getMemory().getTotal() / 1024.0 / 1024 / 1024;
        // 计算磁盘总容量（转换为GB）
        double diskSize = Arrays.stream(File.listRoots()).mapToLong(File::getTotalSpace).sum() / 1024.0 / 1024 / 1024;
        // 获取网络接口IP地址
        String ip = Objects.requireNonNull(this.findNetworkInterface(hardware)).getIPv4addr()[0];

        // 构建并返回基础信息对象
        return new BaseDetail()
                .setOsArch(properties.getProperty("os.arch"))  // 系统架构
                .setOsName(os.getFamily())  // 操作系统名称
                .setOsVersion(os.getVersionInfo().getVersion())  // 系统版本
                .setOsBit(os.getBitness())  // 系统位数
                .setCpuName(hardware.getProcessor().getProcessorIdentifier().getName())  // CPU型号
                .setCpuCore(hardware.getProcessor().getLogicalProcessorCount())  // CPU核心数
                .setMemory(memory)  // 内存总量
                .setDisk(diskSize)  // 磁盘总量
                .setIp(ip);  // IP地址
    }

    // 获取运行时监控数据
    public RuntimeDetail monitorRuntimeDetail() {
        double statisticTime = 0.5;  // 统计时间间隔（秒）
        try {
            HardwareAbstractionLayer hardware = info.getHardware();
            NetworkIF networkInterface = Objects.requireNonNull(this.findNetworkInterface(hardware));
            CentralProcessor processor = hardware.getProcessor();

            // 初始网络和磁盘数据采集
            double upload = networkInterface.getBytesSent();
            double download = networkInterface.getBytesRecv();
            double read = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum();
            double write = hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum();
            long[] ticks = processor.getSystemCpuLoadTicks();  // 获取CPU时钟周期
            
            Thread.sleep((long) (statisticTime * 1000));  // 等待统计间隔
            
            // 二次采集数据计算差值
            networkInterface = Objects.requireNonNull(this.findNetworkInterface(hardware));
            upload = (networkInterface.getBytesSent() - upload) / statisticTime;  // 上传速度（B/s）
            download = (networkInterface.getBytesRecv() - download) / statisticTime;  // 下载速度（B/s）
            read = (hardware.getDiskStores().stream().mapToLong(HWDiskStore::getReadBytes).sum() - read) / statisticTime;
            write = (hardware.getDiskStores().stream().mapToLong(HWDiskStore::getWriteBytes).sum() - write) / statisticTime;

            // 内存使用量计算（GB）
            double memory = (hardware.getMemory().getTotal() - hardware.getMemory().getAvailable()) / 1024.0 / 1024 / 1024;
            // 磁盘使用量计算（GB）
            double disk = Arrays.stream(File.listRoots())
                    .mapToLong(file -> file.getTotalSpace() - file.getFreeSpace()).sum() / 1024.0 / 1024 / 1024;

            // 构建并返回运行时数据对象
            return new RuntimeDetail()
                    .setCpuUsage(this.calculateCpuUsage(processor, ticks))  // CPU使用率
                    .setMemoryUsage(memory)  // 内存使用量
                    .setDiskUsage(disk)  // 磁盘使用量
                    .setNetworkUpload(upload / 1024)  // 转换为KB/s
                    .setNetworkDownload(download / 1024)  // 转换为KB/s
                    .setDiskRead(read / 1024 / 1024)  // 转换为MB/s
                    .setDiskWrite(write / 1024 / 1024)  // 转换为MB/s
                    .setTimestamp(new Date().getTime());  // 时间戳
        } catch (Exception e) {
            log.error("读取运行时数据出现问题", e);
        }
        return null;
    }

    // 计算CPU使用率
    private double calculateCpuUsage(CentralProcessor processor, long[] prevTicks) {
        // 获取最新的CPU时钟周期
        long[] ticks = processor.getSystemCpuLoadTicks();
        // 计算各类型 CPU 时间片的增量值（单位：tick）
        // Nice时间 - 低优先级用户进程的CPU时间
        long nice = ticks[CentralProcessor.TickType.NICE.getIndex()] - prevTicks[CentralProcessor.TickType.NICE.getIndex()];
        // 硬件中断时间 - 硬件设备中断消耗的CPU时间
        long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()] - prevTicks[CentralProcessor.TickType.IRQ.getIndex()];
        // 软件中断时间 - 内核处理软中断消耗的CPU时间
        long softIrq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()] - prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        // Steal时间 - 虚拟化环境中被其他虚拟机占用的CPU时间
        long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()] - prevTicks[CentralProcessor.TickType.STEAL.getIndex()];
        // 系统时间 - 内核态运行的CPU时间
        long cSys = ticks[CentralProcessor.TickType.SYSTEM.getIndex()] - prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
        // 用户时间 - 用户态运行的CPU时间
        long cUser = ticks[CentralProcessor.TickType.USER.getIndex()] - prevTicks[CentralProcessor.TickType.USER.getIndex()];
        // I/O等待时间 - CPU等待I/O操作完成的时间
        long ioWait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()] - prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
        // 空闲时间 - CPU未执行任务的时间
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] - prevTicks[CentralProcessor.TickType.IDLE.getIndex()];

        // 计算总CPU时间
        long totalCpu = cUser + nice + cSys + idle + ioWait + irq + softIrq + steal;
        // 返回使用率（系统+用户时间 / 总时间）
        return (cSys + cUser) * 1.0 / totalCpu;
    }

    // 获取所有网络接口名称列表
    public List<String> listNetworkInterfaceName() {
        return info.getHardware().getNetworkIFs()
                .stream()
                .map(NetworkIF::getName)
                .toList();
    }

    // 查找指定网络接口
    private NetworkIF findNetworkInterface(HardwareAbstractionLayer hardware) {
        try {
            String target = config.getNetworkInterface();  // 从配置获取目标网卡名称
            // 过滤匹配的网卡
            List<NetworkIF> ifs = hardware.getNetworkIFs()
                    .stream()
                    .filter(inter -> inter.getName().equals(target))
                    .toList();
            
            if (!ifs.isEmpty()) {
                return ifs.get(0);  // 返回第一个匹配的网卡
            } else {
                throw new IOException("网卡信息错误，找不到网卡: " + target);
            }
        } catch (IOException e) {
            log.error("读取网络接口信息时出错", e);
        }
        return null;
    }
}
