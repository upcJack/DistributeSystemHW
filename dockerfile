# 使用Ubuntu 20.04作为基础镜像
FROM ubuntu:20.04


# 更新apt-get源
RUN apt-get update

# 安装Java运行时环境
RUN apt-get install -y default-jre

# 在容器中创建一个新目录用于存放应用程序
WORKDIR /app

# 将test.jar复制到容器的/app目录下
COPY test.jar /app

# 指定容器的启动命令，运行test.jar
CMD ["java", "-jar", "test.jar"]
