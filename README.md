Temi Yolo - Object Detection and Localization on a Temi Robot
========

![temi](temi.jpg)

This repository is used to store the proof-of-concept all the relevant code and data that I used for my thesis "Towards a Smarter Robot".
With this research we implemented object detection and localizing on a Temi robot, thus making it a smarter robot.

The repository contains the following elements:
- Android application;
- Evaluation data;
- The converted models in NCNN format.

For more information the Temi robot check [this page](https://www.robotemi.com/developers/).

-----
## Android application
The Android application created for this project was based on the Temi SDK sample application, which can be found [here][https://github.com/robotemi/sdk/]. 
The application was modified to include the YOLO object detection model and object localization via triangulation.

### Starting the application
To run the application, you need to have the Temi robot and the Android Studio installed.
The application can be run on the robot by connecting the robot to the computer via ADB, this requires using the developer tools on the Temi robot.
After that, start the application with the play button in Android Studio.

Extended information on how to run the application can be found in a document made by me and Friso Turkstra called run_app.pdf.

-----
## Evaluation data
During the two evaluation sessions several photos were made by the Temi robot. These photos, including their scores can be found in the folder `evaluation_data`.

### Object detection data

### Localization data

-----
## Converted model
The converted YOLOv8 model converted to NCNN format that we used in the Android Application can be found in the folder `models`.
- Yolo Extra-large: `yolov8c.bin` and `yolov8c.param` -> used as default model;
- Yolo Small: `yolov8s.bin` and `yolov8s.param`.

-----
## References
Temi SDK used to communicate with the Temi robot & the example app this app was based on. https://github.com/robotemi/sdk/

YOLO v8 model used in the application. https://github.com/ultralytics/

NCNN format used in the application to use the YOLOv8 model. https://github.com/Tencent/ncnn

OpenCV for image loading and processing. https://github.com/opencv/opencv

Nanodet implementation. https://github.com/nihui/ncnn-android-nanodet

Example of a YOLO implementation. https://github.com/FeiGeChuanShu/ncnn-android-yolov8

ChatGPT and GitHub Copilot general programming help such as advanced position calculations.